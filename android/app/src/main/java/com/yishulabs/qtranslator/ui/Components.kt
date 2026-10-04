package com.yishulabs.qtranslator.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.conversation.ConversationStore
import com.yishulabs.qtranslator.conversation.SceneCover
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import kotlinx.coroutines.delay

/** 读入一张图片：按 EXIF 方向转正，长边缩到 2400 像素以内 */
fun loadBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            if (side > 2400) {
                val ratio = 2400f / side
                decoder.setTargetSize((info.size.width * ratio).toInt(), (info.size.height * ratio).toInt())
            }
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?.let { ConversationStore.scaled(it, 2400) }
    }
}.getOrNull()

/** 场景图标：数据里存的是苹果版的图标名，这里对应到 Material 图标 */
fun sceneIcon(symbol: String?): ImageVector = when (symbol) {
    "book.closed.fill" -> Icons.AutoMirrored.Rounded.MenuBook
    "graduationcap.fill" -> Icons.Rounded.School
    "sun.max.fill" -> Icons.Rounded.WbSunny
    "leaf.fill" -> Icons.Rounded.Eco
    "house.fill" -> Icons.Rounded.Home
    "airplane" -> Icons.Rounded.AirplanemodeActive
    "fork.knife" -> Icons.Rounded.Restaurant
    "cart.fill" -> Icons.Rounded.ShoppingCart
    "briefcase.fill" -> Icons.Rounded.Work
    "cross.case.fill" -> Icons.Rounded.MedicalServices
    "bubble.left.and.bubble.right.fill" -> Icons.Rounded.Forum
    "star.fill" -> Icons.Rounded.Star
    else -> Icons.Rounded.Inbox
}

/** 场景封面小方块 */
@Composable
fun SceneCoverView(cover: SceneCover?, size: Dp = 40.dp) {
    val (background, foreground) = ScenePalette.color(cover?.palette ?: 5)
    Box(
        Modifier.size(size).background(background, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(sceneIcon(cover?.symbol), null, tint = foreground, modifier = Modifier.size(size * 0.5f))
    }
}

/** 朗读按钮：正在读这一段时变成停止 */
@Composable
fun SpeakButton(speech: Speech, tint: Color = Lx.colors.accent) {
    val playing = Speaker.playing == speech
    IconButton(onClick = { Speaker.toggle(speech) }) {
        Icon(
            if (playing) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
            if (playing) "停止朗读" else "朗读", tint = tint,
        )
    }
}

/** “英 /tʃɑːdʒ/ 🔊” 这样的发音按钮 */
@Composable
fun PronunciationPill(label: String, text: String, speech: Speech) {
    val colors = Lx.colors
    val playing = Speaker.playing == speech
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface2)
            .clickable { Speaker.toggle(speech) }
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label, color = colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(colors.accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(text, fontSize = 16.sp, color = colors.ink)
        Icon(
            if (playing) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, if (playing) "停止" else "朗读",
            tint = colors.accent, modifier = Modifier.size(18.dp),
        )
    }
}

/** 小圆角标签，例如 “✓ 本机离线翻译” */
@Composable
fun Pill(
    text: String,
    icon: ImageVector?,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .height(height)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = foreground, modifier = Modifier.size(16.dp))
        Text(text, color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink3)
}

/**
 * 超过几行（默认 4 行）时折叠；只有真的放不下时才出现“展开全文 / 收起”。
 * aiBadge：在文字末尾接一个小小的“AI”标记（用了 AI 优化的译文）
 */
@Composable
fun FoldableText(
    text: String,
    style: TextStyle,
    expanded: Boolean,
    onToggle: () -> Unit,
    alignEnd: Boolean = false,
    color: Color = Lx.colors.ink,
    foldedLines: Int = 4,
    aiBadge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var overflows by remember(text) { mutableStateOf(false) }
    val aiColor = Lx.colors.ai
    val content = remember(text, aiBadge, aiColor) {
        buildAnnotatedString {
            append(text)
            if (aiBadge) withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = aiColor)) { append("  ✦AI") }
        }
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            content, style = style, color = color,
            maxLines = if (expanded) Int.MAX_VALUE else foldedLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { result -> overflows = if (expanded) result.lineCount > foldedLines else result.hasVisualOverflow },
        )
        if (overflows) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Lx.colors.accent, modifier = Modifier.size(18.dp))
                Text(if (expanded) "收起" else "展开全文", color = Lx.colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 快捷复制：小图标，点一下复制，变成对勾一会儿；有 title 时显示成“图标 + 文字” */
@Composable
fun CopyButton(text: String, label: String, modifier: Modifier = Modifier, title: String? = null) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val tint = if (copied) Color(0xFF2FA84F) else Lx.colors.ink3
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = label) {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
            .padding(horizontal = if (title != null) 6.dp else 0.dp)
            .semantics { contentDescription = if (copied) "已复制" else label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, tint = tint, modifier = Modifier.size(if (title != null) 14.dp else 16.dp))
        if (title != null) Text(if (copied) "已复制" else title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

/** 圆形图标按钮，带浅色底 */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Lx.colors.ink) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Lx.colors.background.copy(alpha = 0.9f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint)
    }
}
