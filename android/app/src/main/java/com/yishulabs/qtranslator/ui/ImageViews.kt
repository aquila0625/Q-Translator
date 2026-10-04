package com.yishulabs.qtranslator.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.ImageBlock
import com.yishulabs.qtranslator.ui.modules.ConfirmDelete
import com.yishulabs.qtranslator.ui.modules.PendingDelete
import kotlin.math.roundToInt

/** 一张图片，译文覆盖在原文的位置上；showTranslation 为 false 时显示原图。overlay 放在图片自己的范围里（例如右上角的切换） */
@Composable
fun TranslatedImage(
    bitmap: Bitmap,
    blocks: List<ImageBlock>,
    showTranslation: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val ratio = bitmap.width.toFloat() / maxOf(bitmap.height, 1)
    BoxWithConstraints(modifier.aspectRatio(ratio)) {
        Image(image, description, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        if (showTranslation) {
            blocks.filter { it.translation.isNotEmpty() }.forEach { OverlayLabel(it, maxWidth, maxHeight) }
        }
        overlay()
    }
}

/** 盖在原文上的一块译文：用原文周围的底色完全盖住原文，深底配白字、浅底配黑字；字号放不下时自动缩小 */
@Composable
private fun OverlayLabel(block: ImageBlock, imageWidth: Dp, imageHeight: Dp) {
    val turns = ((block.turns ?: 0) % 4 + 4) % 4
    // 屏幕上这一块占的大小；转过 90° / 270° 时，文字是横着排的，排版用的宽高要对调
    val boxWidth = maxOf(imageWidth * block.width.toFloat(), 12.dp)
    val boxHeight = maxOf(imageHeight * block.height.toFloat(), 10.dp)
    val sideways = turns % 2 == 1
    val width = if (sideways) boxHeight else boxWidth
    val height = if (sideways) boxWidth else boxHeight
    // 按原文的行高估一个字号，放不下时再缩小
    val lineHeight = height / maxOf(block.lines, 1)
    val fontSize = (lineHeight * 0.78f).coerceIn(7.dp, 40.dp)
    val centerX = imageWidth * (block.x + block.width / 2).toFloat()
    val centerY = imageHeight * (block.y + block.height / 2).toFloat()
    // 文字区比原文大一点，左右再留 3dp
    val areaWidth = width + 8.dp
    val areaHeight = height + 8.dp
    val outerWidth = areaWidth + 6.dp
    val background = block.background ?: 0xFFFFFF
    val r = ((background shr 16) and 0xFF).toInt()
    val g = ((background shr 8) and 0xFF).toInt()
    val b = (background and 0xFF).toInt()
    val dark = (0.299 * r + 0.587 * g + 0.114 * b) / 255 < 0.55

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember(block.translation, fontSize, areaWidth, areaHeight, density) {
        with(density) {
            val maxWidth = areaWidth.roundToPx()
            val maxHeight = areaHeight.toPx()
            var size = fontSize.toPx()
            val smallest = size * 0.25f
            var fitted = TextStyle(fontSize = size.toSp(), fontWeight = FontWeight.Medium)
            while (true) {
                fitted = TextStyle(fontSize = size.toSp(), fontWeight = FontWeight.Medium)
                val result = measurer.measure(block.translation, fitted, constraints = Constraints(maxWidth = maxWidth))
                if ((result.size.height <= maxHeight && !result.didOverflowWidth) || size <= smallest) break
                size = maxOf(smallest, size * 0.9f)
            }
            fitted
        }
    }
    Box(
        Modifier
            .offset(x = centerX - outerWidth / 2, y = centerY - areaHeight / 2)
            .size(outerWidth, areaHeight)
            .rotate(turns * 90f)
            .background(Color(r, g, b), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp)
            .semantics { contentDescription = block.translation },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(block.translation, style = style, color = if (dark) Color.White else Color.Black)
    }
}

/** 输入框上方的待发图片：点一下预览，长按左右拖动排序，右上角的叉删除 */
@Composable
fun AttachmentTray(controller: ConversationController) {
    var previewing by remember { mutableStateOf<ConversationController.PendingImage?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    val items = controller.pendingImages
    val step = with(LocalDensity.current) { 68.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                var drag by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = drag
                            val scale = if (dragging) 1.08f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        // 长按后左右拖：松手时按拖过的距离换到新位置
                        .pointerInput(item.id, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = true },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount.x
                                },
                                onDragEnd = {
                                    controller.movePending(item.id, index + (drag / step).roundToInt())
                                    drag = 0f
                                    dragging = false
                                },
                                onDragCancel = {
                                    drag = 0f
                                    dragging = false
                                },
                            )
                        },
                ) {
                    val thumb = remember(item) { item.thumb.asImageBitmap() }
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(10.dp))
                            .border(if (dragging) 2.dp else 1.dp, if (dragging) Lx.colors.accent else Lx.colors.line, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "预览") { previewing = item }
                            .semantics { contentDescription = "第 ${index + 1} 张待发图片，点按预览" },
                    ) {
                        Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (items.size > 1) {
                            Text(
                                "${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                                    .defaultMinSize(16.dp, 16.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-14).dp).size(36.dp).clip(CircleShape)
                            .clickable {
                                pendingDelete = PendingDelete("删除第 ${index + 1} 张图片？", "这张图片还没发送，删除后要重新拍照或选择。") {
                                    controller.removePending(item.id)
                                }
                            }
                            .semantics { contentDescription = "移除第 ${index + 1} 张图片" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(16.dp).background(Color.White, CircleShape))
                        Icon(Icons.Rounded.Cancel, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    previewing?.let { item -> PendingImagePreview(controller, item, onDismiss = { previewing = null }) }
    ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null })
}

/** 预览一张待发的图片，可以删除 */
@Composable
private fun PendingImagePreview(controller: ConversationController, item: ConversationController.PendingImage, onDismiss: () -> Unit) {
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    val image = remember(item) { item.bitmap.asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                Box(Modifier.weight(1f))
                TextButton(onClick = {
                    pendingDelete = PendingDelete("删除这张图片？", "这张图片还没发送，删除后要重新拍照或选择。") {
                        controller.removePending(item.id)
                        onDismiss()
                    }
                }) { Text("删除", color = Color(0xFFFF6B5E)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null })
    }
}
