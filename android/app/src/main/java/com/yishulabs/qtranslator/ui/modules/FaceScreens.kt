package com.yishulabs.qtranslator.ui.modules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.analytics.AnalyticsPage
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.QuickTranslator
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.modules.AppModule
import com.yishulabs.qtranslator.modules.DialogLine
import com.yishulabs.qtranslator.modules.ModuleStore
import com.yishulabs.qtranslator.ui.CopyButton
import com.yishulabs.qtranslator.ui.Lx
import com.yishulabs.qtranslator.ui.VoiceWave
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 面对面对话：首页、对话记录、进行中的对话页。对应苹果版 ModuleViews.swift 的 FaceHome / DialogRecordPage
// 和 FaceToFaceView.swift。

private val Recording = Color(0xFFE5372B)
private fun dialogTime(time: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))

@Composable
fun FaceHome(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: () -> Unit) {
    val module = AppModule.FACE
    ModuleScroll(top) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(module.card).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "我说中文 · 对方说英语", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = module.ink,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Lx.colors.background.copy(alpha = 0.8f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Row(
                Modifier.height(56.dp).clip(RoundedCornerShape(50)).background(module.ink).clickable(onClick = onStart)
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Groups, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("开始对话", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                "手机平放在桌上，上半屏倒过来给对方看；各自按自己的按钮说话，自动翻译并朗读。",
                fontSize = 13.sp, color = module.ink, textAlign = TextAlign.Center,
            )
        }
        ModuleCaption("对话记录")
        RecordList(
            ids = ModuleStore.dialogs.map { it.id }, emptyText = "还没有对话记录",
            deleteTitle = { "删除这次对话？" }, onDelete = { ModuleStore.deleteDialog(it) },
        ) { id ->
            ModuleStore.dialog(id)?.let { record ->
                ModuleRow(
                    module, title = record.lines.firstOrNull()?.original ?: "对话",
                    meta = "${dialogTime(record.createdAt)} · ${record.lines.size} 句",
                    pill = "查看", onOpen = { onOpen(id) }, onPill = { onOpen(id) },
                )
            }
        }
    }
}

@Composable
fun DialogRecordPage(id: String, onBack: () -> Unit) {
    val record = ModuleStore.dialog(id)
    if (record == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var confirm by remember { mutableStateOf<PendingDelete?>(null) }
    Column(Modifier.fillMaxSize()) {
        ModuleTopBar("面对面对话", onBack) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable {
                    confirm = PendingDelete("删除这次对话？") {
                        ModuleStore.deleteDialog(id)
                        onBack()
                    }
                }.semantics { contentDescription = "删除" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Delete, null, tint = Lx.colors.ink2) }
        }
        Text(
            "${dialogTime(record.createdAt)} · ${record.lines.size} 句", fontSize = 13.sp, color = Lx.colors.ink3,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            DialogBubbles(record.lines, Modifier.widthIn(max = 640.dp).padding(16.dp))
        }
        CopyButton(
            text = record.lines.joinToString("\n\n") { it.original + "\n" + it.translation },
            label = "复制全部对话", title = "复制全部",
            modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(bottom = 8.dp),
        )
    }
    ConfirmDelete(confirm, onDismiss = { confirm = null })
}

/** 一段面对面对话的全部句子：对方在左、我在右，译文在上、原话在下；长按复制或朗读 */
@Composable
fun DialogBubbles(lines: List<DialogLine>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (line.isMine) Alignment.CenterEnd else Alignment.CenterStart) {
                Bubble(line)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(line: DialogLine) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (line.isMine) 16.dp else 4.dp, bottomEnd = if (line.isMine) 4.dp else 16.dp,
    )
    Box(Modifier.padding(start = if (line.isMine) 40.dp else 0.dp, end = if (line.isMine) 0.dp else 40.dp)) {
        Column(
            Modifier.clip(shape).background(if (line.isMine) Lx.colors.accent else Lx.colors.background)
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(line.translation, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (line.isMine) Color.White else Lx.colors.ink)
            Text(line.original, fontSize = 13.sp, color = if (line.isMine) Color.White.copy(alpha = 0.8f) else Lx.colors.ink3)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text("复制译文") }, onClick = { clipboard.setText(AnnotatedString(line.translation)); menu = false })
            DropdownMenuItem({ Text("复制原话") }, onClick = { clipboard.setText(AnnotatedString(line.original)); menu = false })
            DropdownMenuItem({ Text("朗读译文") }, onClick = {
                Speaker.toggle(Speech.text(line.translation, isChinese = !line.originalIsChinese))
                menu = false
            })
        }
    }
}

/**
 * 面对面对话：手机平放在桌上，上半屏倒过来给对方看。
 * 各自按自己的大按钮说话，说完自动翻译成另一种语言，大字显示并朗读。退出时整段对话保存到面对面模块的记录里。
 */
@Composable
fun FaceToFaceScreen(controller: ConversationController, onClose: () -> Unit) {
    val lines = remember { mutableStateListOf<DialogLine>() }
    /** 正在说话的一方：true 是我（中文），false 是对方（英语） */
    var speakingMine by remember { mutableStateOf<Boolean?>(null) }
    var translating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val withMic = rememberMicPermission()

    AnalyticsPage("面对面对话")
    LaunchedEffect(Unit) {
        Analytics.track(Analytics.Event.FACE_START)
        // 先把本机翻译模型的状态查好，第一句翻得快一些
        QuickTranslator.refresh()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (VoiceInput.isListening) VoiceInput.cancel()
            Speaker.stop()
            Analytics.track(Analytics.Event.FACE_FINISH, mapOf("lines" to Analytics.bucket(lines.size, listOf(0, 4, 10, 30))))
            ModuleStore.addDialog(lines.toList())
        }
    }
    BackHandler(onBack = onClose)

    fun start(mine: Boolean) {
        Speaker.stop()
        speakingMine = mine
        withMic { VoiceInput.start(if (mine) VoiceInput.Language.CHINESE else VoiceInput.Language.ENGLISH) }
    }

    fun finish() {
        val mine = speakingMine ?: return
        val result = VoiceInput.stop()
        // 面对面不需要回放原声
        result.audio?.let { controller.store.deleteAudioFile(it) }
        val text = result.text
        if (text.isBlank()) {
            speakingMine = null
            return
        }
        translating = true
        scope.launch {
            val translation = QuickTranslator.translate(text, fromChinese = mine) ?: "（翻译失败）"
            translating = false
            lines += DialogLine(isMine = mine, original = text, translation = translation, originalIsChinese = mine)
            speakingMine = null
            if (Prefs.dialogSpeak) Speaker.play(Speech.text(translation, isChinese = !mine))
        }
    }

    Box(Modifier.fillMaxSize().background(Lx.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            Half(mine = false, lines.lastOrNull(), speakingMine, translating, Modifier.weight(1f).rotate(180f), ::start, ::finish)
            Half(mine = true, lines.lastOrNull(), speakingMine, translating, Modifier.weight(1f), ::start, ::finish)
        }
        // 中间：退出、朗读开关
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundControl(Icons.Rounded.Close, "结束对话", onClose)
            RoundControl(
                if (Prefs.dialogSpeak) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                if (Prefs.dialogSpeak) "关闭朗读" else "打开朗读",
            ) {
                Prefs.updateDialogSpeak(!Prefs.dialogSpeak)
                if (!Prefs.dialogSpeak) Speaker.stop()
            }
        }
    }
}

/** 一方的半屏：显示最近一句，用这一方的语言大字显示（自己说的显示原话，对方说的显示译文） */
@Composable
private fun Half(
    mine: Boolean,
    last: DialogLine?,
    speakingMine: Boolean?,
    translating: Boolean,
    modifier: Modifier,
    onStart: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = Lx.colors
    val listening = speakingMine == mine && VoiceInput.isListening
    val background = if (mine) colors.surface else colors.sentenceCard
    // 对方那一半是倒过来的：它自己的“底边”在屏幕最上面，要让出状态栏
    val edge = if (mine) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        modifier.fillMaxWidth().background(background).padding(bottom = edge)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            when {
                listening -> {
                    Text(if (mine) "正在听你说…" else "Listening…", fontSize = 15.sp, color = colors.ink3)
                    Spacer(Modifier.height(12.dp))
                    val text = VoiceInput.text
                    Text(
                        text.ifEmpty { if (mine) "请说中文" else "Please speak English" }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        color = if (text.isEmpty()) colors.ink3 else colors.ink, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    )
                }
                translating && speakingMine == !mine -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (mine) "正在翻译…" else "Translating…", fontSize = 15.sp, color = colors.ink3)
                }
                last != null -> {
                    val ownWords = last.isMine == mine
                    Text(if (ownWords) (if (mine) "我说" else "You said") else (if (mine) "对方说" else "They said"), fontSize = 15.sp, color = colors.ink3)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (ownWords) last.original else last.translation, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.ink, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(if (ownWords) last.translation else last.original, fontSize = 16.sp, color = colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                else -> Text(
                    if (mine) "按下面的按钮说中文" else "Tap the button below to speak English",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink3,
                )
            }
        }
        TalkButton(mine, listening, translating, onStart, onFinish)
    }
}

/** 大按钮：点一下开始说，再点一下说完 */
@Composable
private fun TalkButton(mine: Boolean, listening: Boolean, translating: Boolean, onStart: (Boolean) -> Unit, onFinish: () -> Unit) {
    val othersTurn = VoiceInput.isListening && !listening
    val enabled = !translating && !othersTurn
    val tint = if (mine) Lx.colors.sentenceInk else Lx.colors.accent
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).alpha(if (othersTurn) 0.4f else 1f).clip(RoundedCornerShape(50))
            .background(if (listening) Recording else tint)
            .clickable(enabled = enabled) { if (listening) onFinish() else onStart(mine) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, null, tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(
            if (listening) (if (mine) "说完了" else "Done") else (if (mine) "我说中文" else "Speak English"),
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
        )
        if (listening) {
            Spacer(Modifier.width(10.dp))
            VoiceWave(VoiceInput.levels.takeLast(10), Modifier.width(50.dp))
        }
    }
}

@Composable
private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Lx.colors.ink).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Lx.colors.background) }
}
