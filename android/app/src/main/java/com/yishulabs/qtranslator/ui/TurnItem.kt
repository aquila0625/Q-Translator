package com.yishulabs.qtranslator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.HistoryStore
import com.yishulabs.qtranslator.conversation.Turn
import com.yishulabs.qtranslator.conversation.TurnImage
import com.yishulabs.qtranslator.conversation.TurnKind
import com.yishulabs.qtranslator.conversation.TurnState
import com.yishulabs.qtranslator.core.OnlineTranslator
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.SentenceResult
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.core.WordEntry
import com.yishulabs.qtranslator.core.isMostlyChinese

/** 和会话里这类卡片相同的底色 */
val TurnKind.cardColor: Color
    @Composable get() = when (this) {
        TurnKind.WORD -> Lx.colors.surface
        TurnKind.SENTENCE -> Lx.colors.sentenceCard
        TurnKind.IMAGE -> Lx.colors.imageCard
    }

/** 和卡片底色配套的深色：筛选按钮的文字和选中时的底色 */
val TurnKind.inkColor: Color
    @Composable get() = when (this) {
        TurnKind.WORD -> Lx.colors.accent
        TurnKind.SENTENCE -> Lx.colors.sentenceInk
        TurnKind.IMAGE -> Lx.colors.imageInk
    }

/**
 * 会话里的一轮。每种内容有固定的样子，看一眼就分得清：
 * 单词是浅蓝色的词典卡片；句子的原文和译文一起放在浅绿色卡片里；图片的译文直接覆盖在图上。
 * 操作按钮只在最新一轮和被点选的那一轮出现（showsActions），点一下这一轮选中它（onSelect）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TurnItem(
    turn: Turn,
    controller: ConversationController,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onOpenWord: (WordEntry) -> Unit,
    onReply: (SentenceResult) -> Unit,
    onOpenImage: (String) -> Unit,
    onNeedAI: () -> Unit,
    showsActions: Boolean = false,
    onSelect: () -> Unit = {},
) {
    val colors = Lx.colors
    var menu by remember { mutableStateOf(false) }
    val sourceMenu: @Composable () -> Unit = {
        SourceMenu(turn, menu, onDismiss = { menu = false }, onEdit = { onEditingChange(true) })
    }
    if (turn.isImage || turn.word != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 单词直接显示成词卡，不再重复一个原文气泡
            if (turn.isImage) ImageSource(turn)
            if (editing) SourceEditor(turn, controller, onDone = { onEditingChange(false) })
            turn.audioFile?.let { audio ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Mic, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    AudioReplayButton(audio, turn.audioDuration)
                }
            }
            TagLine(turn)
            Box {
                Result(turn, controller, expanded, onToggleExpand, onOpenWord, onReply, onOpenImage, onNeedAI, showsActions, onSelect, onLongPress = { menu = true })
                sourceMenu()
            }
        }
    } else {
        // 一句话或一段话：原文和译文放在同一张浅绿色卡片里，算一组
        Box {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.sentenceCard)
                    .combinedClickable(onClick = onSelect, onLongClick = { menu = true })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editing) {
                    SourceEditor(turn, controller, onDone = { onEditingChange(false) })
                } else {
                    SentenceSource(turn, expanded, onToggleExpand, onSelect)
                }
                TagLine(turn)
                HorizontalDivider(color = colors.line.copy(alpha = colors.line.alpha * 0.5f))
                Box(Modifier.alpha(if (editing) 0.4f else 1f)) {
                    Result(turn, controller, expanded, onToggleExpand, onOpenWord, onReply, onOpenImage, onNeedAI, showsActions, onSelect, onLongPress = { menu = true })
                }
            }
            sourceMenu()
        }
    }
}

/** 长按：编辑、复制、朗读原文（删除在左滑里） */
@Composable
private fun SourceMenu(turn: Turn, expanded: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!turn.isImage) {
            DropdownMenuItem(text = { Text("编辑原文") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { onDismiss(); onEdit() })
        }
        DropdownMenuItem(text = { Text("复制原文") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
            onDismiss()
            clipboard.setText(AnnotatedString(turn.source))
        })
        DropdownMenuItem(text = { Text("朗读原文") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) }, onClick = {
            onDismiss()
            Speaker.toggle(Speech.text(turn.source, turn.sourceIsChinese))
        })
    }
}

/** 只在手动指定方向或改过原文时才标出来，平时不显示 */
@Composable
private fun TagLine(turn: Turn) {
    val parts = listOfNotNull(
        if (turn.manualDirection) (if (turn.sourceIsChinese) "中 → 英 · 手动" else "英 → 中 · 手动") else null,
        if (turn.edited) "已编辑" else null,
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "), fontSize = 11.sp, color = Lx.colors.ink3,
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

/** 卡片里的原文：灰色小字，最多两行，右边是快捷复制；语音输入的带麦克风标记和回放 */
@Composable
private fun SentenceSource(turn: Turn, expanded: Boolean, onToggleExpand: () -> Unit, onSelect: () -> Unit) {
    val colors = Lx.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (turn.audioFile != null) {
                Icon(
                    Icons.Rounded.Mic, "语音输入", tint = colors.accent,
                    modifier = Modifier.padding(top = 3.dp, end = 6.dp).size(14.dp),
                )
            }
            FoldableText(
                turn.source, TextStyle(fontSize = 14.sp), expanded, onToggleExpand, color = colors.ink2, foldedLines = 2,
                modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = null, onClick = onSelect),
            )
            CopyButton(turn.source, "复制原文", Modifier.offset(x = 10.dp, y = (-10).dp))
        }
        turn.audioFile?.let { AudioReplayButton(it, turn.audioDuration) }
    }
}

@Composable
private fun SourceEditor(turn: Turn, controller: ConversationController, onDone: () -> Unit) {
    var text by remember { mutableStateOf(turn.source) }
    Column(
        Modifier.fillMaxWidth().border(2.dp, Lx.colors.accent, RoundedCornerShape(18.dp)).padding(12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 12, label = { Text("原文") })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone) { Text("取消") }
            Button(onClick = {
                controller.editSource(turn.id, text)
                onDone()
            }, enabled = text.isNotBlank()) { Text("保存并重新翻译") }
        }
    }
}

/** 图片这一轮的“原文”：只显示几张图和附带的要求，图片本身在下面的译文卡片里 */
@Composable
private fun ImageSource(turn: Turn) {
    val colors = Lx.colors
    Column(Modifier.fillMaxWidth().padding(start = 56.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        turn.instruction?.let { instruction ->
            Row(
                Modifier.background(colors.surface, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = colors.ink2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(instruction, fontSize = 14.sp, color = colors.ink2)
            }
        }
        Text(
            "${turn.images.size} 张图片" + if (turn.state == TurnState.WORKING) " · 识别和翻译中" else "",
            fontSize = 11.sp, color = colors.ink3,
        )
    }
}

// 结果

@Composable
private fun Result(
    turn: Turn,
    controller: ConversationController,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenWord: (WordEntry) -> Unit,
    onReply: (SentenceResult) -> Unit,
    onOpenImage: (String) -> Unit,
    onNeedAI: () -> Unit,
    showsActions: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = Lx.colors
    when {
        turn.state == TurnState.WORKING && !turn.isImage -> Working("翻译中…")
        turn.state == TurnState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = colors.ink3, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(turn.errorMessage ?: "翻译失败", fontSize = 13.sp, color = colors.ink3)
            TextButton(onClick = { controller.retry(turn.id) }) { Text("重试", fontWeight = FontWeight.SemiBold) }
        }
        turn.isImage -> ImageResults(turn, controller, showsActions, onSelect, onOpenImage)
        turn.word != null -> WordCard(turn.word, onLongPress = onLongPress) { onOpenWord(turn.word) }
        turn.sentence != null -> SentenceResultView(turn, turn.sentence, controller, expanded, onToggleExpand, onReply, onNeedAI, showsActions, onSelect)
    }
}

@Composable
private fun Working(text: String) {
    Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = Lx.colors.ink3)
    }
}

/** 译文直接覆盖在图上原文的位置；右上角点“原图 / 译文”切换。点图片进入大图 */
@Composable
private fun ImageResults(
    turn: Turn,
    controller: ConversationController,
    showsActions: Boolean,
    onSelect: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val colors = Lx.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // 切到“原图”的那几张图片（默认都显示译文）
    var originals by remember { mutableStateOf(setOf<String>()) }
    val all = turn.images.map { it.translation }.filter { it.isNotEmpty() }.joinToString("\n")
    val multiple = turn.images.size > 1
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.imageCard)
            .clickable(onClick = onSelect).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val toggle: (String) -> Unit = { id -> originals = if (id in originals) originals - id else originals + id }
        if (multiple) {
            // 多张图并排、按各自的宽高比排得紧凑一些，左右滑动
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                turn.images.forEachIndexed { index, item ->
                    ImagePage(turn, item, index, 260.dp, item.id in originals, toggle, controller, onOpenImage)
                }
            }
        } else {
            turn.images.firstOrNull()?.let { ImagePage(turn, it, 0, null, it.id in originals, toggle, controller, onOpenImage) }
        }
        if (showsActions && turn.state == TurnState.DONE && all.isNotEmpty()) {
            Row(Modifier.offset(x = (-12).dp)) {
                SpeakButton(Speech.text(all, !(turn.images.firstOrNull()?.recognized?.isMostlyChinese ?: false)))
                if (multiple) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(all))
                        copied = true
                    }) { Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, "复制全部译文", tint = colors.ink2) }
                }
            }
        }
    }
}

/**
 * 一张图：译文盖在图上，右上角切换原图和译文，下面是页码和这张图译文的快捷复制。
 * height 为 null 时铺满宽度（只有一张图）；多张图时按固定高度、各自的宽高比排
 */
@Composable
private fun ImagePage(
    turn: Turn,
    item: TurnImage,
    index: Int,
    height: Dp?,
    showingOriginal: Boolean,
    onToggleOriginal: (String) -> Unit,
    controller: ConversationController,
    onOpenImage: (String) -> Unit,
) {
    val colors = Lx.colors
    val store = controller.store
    val bitmap = remember(item.fileName, store.imageRevision) { store.thumbnail(item.fileName, 900) }
    val ratio = bitmap?.let { it.width.toFloat() / maxOf(it.height, 1) } ?: 0.75f
    val width = height?.let { (it * ratio).coerceIn(120.dp, 300.dp) }
    val blocks = item.blocks ?: emptyList()
    val hasBlocks = item.done && blocks.isNotEmpty()
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (bitmap != null) {
            Box(
                (if (height != null) Modifier.size(width!!, height) else Modifier.fillMaxWidth().heightIn(max = 460.dp)),
                contentAlignment = if (height != null) Alignment.TopCenter else Alignment.Center,
            ) {
                TranslatedImage(
                    bitmap, blocks, showTranslation = !showingOriginal && item.done,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "看大图") { onOpenImage(item.id) },
                    description = "图 ${index + 1}，点按看大图",
                ) {
                    if (hasBlocks) OriginalToggle(showingOriginal, Modifier.align(Alignment.TopEnd).padding(6.dp)) { onToggleOriginal(item.id) }
                    if (!item.done) {
                        Row(
                            Modifier.align(Alignment.Center).background(colors.background.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("识别和翻译中…", fontSize = 13.sp, color = colors.ink2)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (turn.images.size > 1) {
                Text("${index + 1}/${turn.images.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink3)
            }
            val failed = item.failed == true
            val problem = item.done && (failed || blocks.isEmpty())
            if (problem) {
                Text(
                    if (failed) "识别或翻译失败" else if (item.blocks == null) item.translation else "没有识别到文字",
                    fontSize = 13.sp, color = if (failed) colors.ai else colors.ink3, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (item.done) {
                // 出问题时显示文字按钮，平时只是一个小图标
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { controller.reprocessImage(turn.id, item.id) }
                        .defaultMinSize(36.dp, 36.dp).padding(horizontal = 4.dp)
                        .semantics { contentDescription = "重新识别图 ${index + 1}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    val tint = if (problem) colors.accent else colors.ink3
                    Icon(Icons.Rounded.Refresh, null, tint = tint, modifier = Modifier.size(16.dp))
                    if (problem && (width ?: 999.dp) >= 150.dp) Text("重新识别", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
                }
            }
            if (hasBlocks && item.translation.isNotEmpty()) {
                CopyButton(item.translation, "复制图 ${index + 1} 的译文", title = if ((width ?: 999.dp) >= 210.dp) "复制译文" else null)
            }
        }
    }
}

/** 图片右上角的切换：原图 / 译文，点哪个显示哪个 */
@Composable
private fun OriginalToggle(showingOriginal: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Row(modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(2.dp)) {
        listOf(false, true).forEach { original ->
            val on = showingOriginal == original
            Box(
                Modifier.height(26.dp).clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                    .clickable { if (!on) onToggle() }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (original) "原图" else "译文", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color.White)
            }
        }
    }
}

@Composable
private fun SentenceResultView(
    turn: Turn,
    sentence: SentenceResult,
    controller: ConversationController,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onReply: (SentenceResult) -> Unit,
    onNeedAI: () -> Unit,
    showsActions: Boolean,
    onSelect: () -> Unit,
) {
    val colors = Lx.colors
    var showBefore by remember { mutableStateOf(false) }
    // 单词和短语查不到词典时也走机器翻译，但不提供 AI 优化：AI 只针对一句话
    val isSentence = !controller.isWordLike(sentence.source)
    val waitingForAI = turn.isOptimizing && !sentence.showsAI
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (waitingForAI) {
            // 开着 AI 优化时不先显示机器翻译，等优化好了直接显示结果
            Working("AI 优化中…")
        } else {
            Row(verticalAlignment = Alignment.Top) {
                // 用了 AI 只在句尾标一个小小的“AI”，不再单独一行标签
                FoldableText(
                    sentence.displayed, TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp), expanded, onToggleExpand,
                    aiBadge = sentence.showsAI,
                    modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = null, onClick = onSelect),
                )
                // 译文的快捷复制
                CopyButton(sentence.displayed, "复制译文", Modifier.offset(x = 10.dp, y = (-10).dp))
            }
        }
        turn.aiError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = colors.ai, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(error, fontSize = 13.sp, color = colors.ai)
            }
        }
        if (showsActions) {
            if (sentence.showsAI) {
                if (sentence.aiTranslation != sentence.translation) {
                    // 优化前的译文默认折叠
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { showBefore = !showBefore }.defaultMinSize(minHeight = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (showBefore) Icons.Rounded.ExpandLess else Icons.Rounded.ChevronRight, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
                        Text(if (showBefore) "收起优化前" else "查看优化前的译文", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink3)
                    }
                    if (showBefore) {
                        Text(
                            sentence.translation, fontSize = 13.sp, color = colors.ink2,
                            modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(10.dp),
                        )
                    }
                }
                if (Prefs.showAIUsage) {
                    Text(
                        listOfNotNull(sentence.aiModel, sentence.aiUsage?.summary).joinToString(" · "),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ai,
                    )
                }
            }
            Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                SpeakButton(Speech.text(sentence.displayed, !sentence.sourceIsChinese))
                ActionIcon(Icons.Rounded.SwapVert, "对调：把译文反向再翻译一次") { controller.swap(turn) }
                ActionIcon(Icons.AutoMirrored.Rounded.Reply, "AI 写回复", tint = colors.ai) {
                    if (AISettings.isConfigured) onReply(sentence) else onNeedAI()
                }
                if (isSentence || sentence.showsAI) {
                    VerticalDivider(Modifier.height(20.dp).padding(horizontal = 4.dp), color = colors.line)
                    AIChip(sentence.showsAI, enabled = !turn.isOptimizing) {
                        if (AISettings.isConfigured || sentence.aiTranslation != null) controller.toggleAI(turn.id) else onNeedAI()
                    }
                    if (sentence.showsAI && !turn.isOptimizing) {
                        ActionIcon(Icons.Rounded.Refresh, "重新用 AI 优化", tint = colors.ai) {
                            if (AISettings.isConfigured) controller.reoptimize(turn.id) else onNeedAI()
                        }
                    }
                }
            }
            if (!sentence.showsAI) Text("译文来自" + sentence.engine, fontSize = 11.sp, color = colors.ink3.copy(alpha = 0.7f))
        }
        if (controller.offlineDownloadable && sentence.engine == OnlineTranslator.NAME && showsActions) {
            TextButton(onClick = { controller.downloadOfflineModel() }) {
                Text("下载本机离线翻译模型（约 30 MB，之后更快、不限量、无需联网）", fontSize = 13.sp)
            }
        }
        if (sentence.suggestions.isNotEmpty()) {
            Text("你是不是要找：" + sentence.suggestions.take(3).joinToString("、") { it.word }, fontSize = 13.sp, color = colors.ink3)
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tint: Color = Lx.colors.ink3, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** “AI 优化”开关：点一下用 AI 优化，再点一下回到机器翻译；之前优化过的结果会保留，打开时不用重新请求 */
@Composable
private fun AIChip(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Lx.colors
    Box(
        Modifier.defaultMinSize(minHeight = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "AI 优化：" + if (on) "开" else "关" },
        contentAlignment = Alignment.Center,
    ) {
        Pill(
            "AI", Icons.Rounded.AutoAwesome,
            if (on) colors.ai else colors.ink3,
            if (on) colors.aiSoft else colors.ink3.copy(alpha = 0.12f),
            height = 28.dp,
        )
    }
}

/** 会话里的词典卡片：词头、发音、前几条释义，点按看完整词条 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun WordCard(entry: WordEntry, onLongPress: (() -> Unit)? = null, onOpen: () -> Unit) {
    val colors = Lx.colors
    val starred = HistoryStore.isStarred(entry.word)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface)
            .let { if (onLongPress != null) it.combinedClickable(onClick = {}, onLongClick = onLongPress) else it }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                entry.word, modifier = Modifier.weight(1f), color = colors.ink,
                style = if (entry.isChinese) TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                else TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif),
            )
            // 复制单词和释义
            CopyButton(wordCopyText(entry), "复制单词和释义", Modifier.padding(top = 4.dp))
            IconButton(onClick = {
                HistoryStore.toggleStar(entry.word, entry.summary)
                if (HistoryStore.isStarred(entry.word)) Analytics.track(Analytics.Event.WORD_STAR)
            }) {
                Icon(
                    if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (starred) "从生词本移除" else "加入生词本",
                    tint = if (starred) Color(0xFFF5A623) else colors.ink3,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pronunciations(entry)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry.senses.isEmpty()) {
                entry.definitions.take(3).forEach { d ->
                    Text(d.text + (d.note?.let { "  $it" } ?: ""), fontSize = 15.sp, color = colors.ink, maxLines = 3)
                }
            } else {
                entry.senses.take(4).forEach { sense ->
                    Row {
                        sense.pos?.let {
                            Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, color = colors.accent)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(sense.meaning, fontSize = 15.sp, color = colors.ink)
                    }
                }
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).defaultMinSize(minHeight = 44.dp).widthIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("完整词条：例句、搭配、辨析", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
        }
    }
}

/** 复制的内容：单词加前几条释义 */
private fun wordCopyText(entry: WordEntry): String {
    val meanings = if (entry.senses.isEmpty()) entry.definitions.take(3).map { it.text }
    else entry.senses.take(4).map { listOfNotNull(it.pos, it.meaning).joinToString(" ") }
    return (listOf(entry.word) + meanings).joinToString("\n")
}

@Composable
fun Pronunciations(entry: WordEntry) {
    when {
        entry.isChinese -> PronunciationPill("中", entry.pinyin ?: "朗读", Speech.chinese(entry.word))
        entry.phonetics.isEmpty() -> PronunciationPill("美", "朗读", Speech.english(entry.word, 2))
        else -> entry.phonetics.forEach { p -> PronunciationPill(p.label, "/${p.ipa}/", Speech.english(entry.word, p.accent)) }
    }
}
