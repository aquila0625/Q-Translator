package com.yishulabs.qtranslator.conversation

import com.yishulabs.qtranslator.ai.AIClient
import com.yishulabs.qtranslator.ai.AIException
import kotlinx.serialization.json.Json

// 开着 AI 时整张图的几段一起交给 AI 翻译，可以带用户的要求。对应苹果版 AITasks.translateImageBlocks

/** 返回和 blocks 一一对应的译文；AI 让某段留空（按要求不翻译）时是空字符串 */
internal suspend fun translateImageBlocks(
    blocks: List<String>, instruction: String?, toChinese: Boolean, config: AIClient.Config,
): List<String> {
    val system = """
        You translate text that was recognized (OCR) from a photo in a translation app. The text comes as numbered blocks; each block is one paragraph or label at its own place in the image, and the app draws your translation over the original text at that place.

        Translate every block into ${if (toChinese) "Simplified Chinese" else "English"}. Keep translations about as short as the original so they fit in the same space. Fix obvious OCR mistakes. If the user gives an instruction, follow it; when the instruction says to leave some content out, use an empty string for those blocks.

        Answer with only a JSON array of strings, one per block in the same order, and nothing else: the app parses it.
    """.trimIndent()
    var user = blocks.withIndex().joinToString("\n") { (i, text) -> "<block index=\"${i + 1}\">\n$text\n</block>" }
    if (!instruction.isNullOrBlank()) user += "\n<instruction>\n${instruction.trim()}\n</instruction>"
    val text = AIClient.complete(system, user, config).text
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    val decoded = if (start >= 0 && end > start) {
        runCatching { Json.decodeFromString<List<String>>(text.substring(start, end + 1)) }.getOrNull()
    } else {
        null
    }
    decoded ?: throw AIException("AI 返回的格式不对，可以重试。")
    // 数量对不上时按位置补齐或截断
    return (decoded + List(maxOf(0, blocks.size - decoded.size)) { "" }).take(blocks.size)
}
