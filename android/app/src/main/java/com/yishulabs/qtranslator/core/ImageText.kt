package com.yishulabs.qtranslator.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本机识别图片里的文字（中英文都能认），图片不上传。 */
object ImageText {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(bitmap: Bitmap): String {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        // 每个文字块是一段；块里的行按语言拼起来：英文行之间加空格，中文直接连上
        return result.textBlocks.joinToString("\n") { block ->
            block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }.fold("") { acc, line -> join(acc, line) }
        }.trim()
    }

    /** 一段文字和它在图里的位置（0…1，左上角为原点） */
    data class Block(val text: String, val rect: RectF, val lines: Int)

    /** 按段识别，带位置，用来把译文覆盖回图片上原来的位置 */
    suspend fun recognizeBlocks(bitmap: Bitmap): List<Block> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val width = bitmap.width.toFloat().coerceAtLeast(1f)
        val height = bitmap.height.toFloat().coerceAtLeast(1f)
        val blocks = mutableListOf<Block>()
        for (textBlock in result.textBlocks) {
            // ML Kit 的块大多已经是一段，但有时会把隔得远的几行并在一起：块里再按行距和左右对齐拆开
            var previous: Rect? = null
            var current: Block? = null
            for (line in textBlock.lines) {
                val text = line.text.trim()
                val box = line.boundingBox ?: continue
                if (text.isEmpty()) continue
                val rect = RectF(box.left / width, box.top / height, box.right / width, box.bottom / height)
                val last = current
                val p = previous
                current = if (last != null && p != null && continues(p, box)) {
                    Block(join(last.text, text), RectF(last.rect).apply { union(rect) }, last.lines + 1)
                } else {
                    last?.let { blocks += it }
                    Block(text, rect, 1)
                }
                previous = box
            }
            current?.let { blocks += it }
        }
        return blocks
    }

    /** 这一行接着上一行（同一段）：上下挨着、行距正常、左右有重叠 */
    private fun continues(p: Rect, box: Rect): Boolean {
        val gap = box.top - p.bottom
        val lineHeight = minOf(p.height(), box.height()).toFloat()
        val overlapsHorizontally = box.left < p.right && box.right > p.left
        return gap >= -lineHeight * 0.3f && gap < lineHeight * 0.8f && overlapsHorizontally
    }

    /** 取每段文字周围一圈的颜色（中位数，0xRRGGBB），作为覆盖译文的底色 */
    suspend fun backgroundColors(bitmap: Bitmap, rects: List<RectF>): List<Long> = withContext(Dispatchers.Default) {
        val fallback = rects.map { 0xFFFFFFL }
        val small = runCatching { thumbnail(bitmap, 640) }.getOrNull() ?: return@withContext fallback
        val width = small.width
        val height = small.height
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        rects.map { rect ->
            // 往外扩一点，沿着四条边取样
            val margin = 3.0
            val minX = maxOf(0, (rect.left * width - margin).toInt())
            val maxX = minOf(width - 1, (rect.right * width + margin).toInt())
            val minY = maxOf(0, (rect.top * height - margin).toInt())
            val maxY = minOf(height - 1, (rect.bottom * height + margin).toInt())
            if (maxX <= minX || maxY <= minY) return@map 0xFFFFFFL
            val reds = mutableListOf<Int>()
            val greens = mutableListOf<Int>()
            val blues = mutableListOf<Int>()
            fun sample(x: Int, y: Int) {
                val color = pixels[y * width + x]
                reds += (color shr 16) and 0xFF
                greens += (color shr 8) and 0xFF
                blues += color and 0xFF
            }
            val stepX = maxOf(1, (maxX - minX) / 24)
            val stepY = maxOf(1, (maxY - minY) / 8)
            for (x in minX..maxX step stepX) { sample(x, minY); sample(x, maxY) }
            for (y in minY..maxY step stepY) { sample(minX, y); sample(maxX, y) }
            fun median(values: List<Int>): Long = values.sorted()[values.size / 2].toLong()
            (median(reds) shl 16) or (median(greens) shl 8) or median(blues)
        }
    }

    /** 取色用的小图；硬件位图读不了像素，先拷成普通位图 */
    private fun thumbnail(bitmap: Bitmap, maxSide: Int): Bitmap {
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val side = maxOf(source.width, source.height)
        if (side <= maxSide) return source
        val ratio = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(
            source, maxOf(1, (source.width * ratio).toInt()), maxOf(1, (source.height * ratio).toInt()), true,
        )
    }

    /** 折行处拼回去：两边都是中文时直接连上，否则加空格；行尾连字符去掉 */
    private fun join(acc: String, line: String): String = when {
        acc.isEmpty() -> line
        acc.endsWith("-") -> acc.dropLast(1) + line
        acc.last().isCjk() && line.first().isCjk() -> acc + line
        else -> "$acc $line"
    }

    private fun Char.isCjk(): Boolean = this in '一'..'鿿' || this in '㐀'..'䶿' || this in '　'..'〿' || this in '＀'..'￯'
}
