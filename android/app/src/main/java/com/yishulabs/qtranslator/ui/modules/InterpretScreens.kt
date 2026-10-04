package com.yishulabs.qtranslator.ui.modules

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.InterpretAITasks
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.core.Interpreter
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.modules.AppModule
import com.yishulabs.qtranslator.modules.InterpretRecord
import com.yishulabs.qtranslator.modules.InterpretSession
import com.yishulabs.qtranslator.modules.ModuleStore
import com.yishulabs.qtranslator.ui.Lx
import com.yishulabs.qtranslator.ui.RoundIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 同声传译：首页、记录全文、进行中的传译页、收起后的小提示条。对应苹果版 ModuleViews.swift 的
// InterpretHome / InterpretRecordPage、InterpreterView.swift、InterpretSession.swift 的 InterpretMiniBar。
// 收音、断句、翻译在 core/Interpreter.kt，全局的传译会话在 modules/InterpretSession.kt。

private val Danger = Color(0xFFE5372B)
private val Paused = Color(0xFFFF9500)

/** 开始一段传译（新开或接着 continuing 那条录）。离开页面也继续，已经在传译时只是回到传译页 */
fun beginInterpretation(continuing: String?, controller: ConversationController) {
    InterpretSession.begin(continuing)
}

/** 时长：分:秒 */
private fun formatDuration(seconds: Double): String {
    val s = Math.round(seconds).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatDate(time: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))

private fun directionTitle(fromChinese: Boolean) = if (fromChinese) "中 → 英" else "英 → 中"

private fun directionOption(fromChinese: Boolean) = if (fromChinese) "听中文，译成英语" else "听英语，译成中文"

/**
 * 开始传译前要的权限：麦克风，以及安卓 13 起后台传译的常驻通知。
 * 不管同不同意都接着执行：没有麦克风权限时传译页会提示并可以重试
 */
@Composable
private fun rememberInterpretPermissions(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return remember(context, launcher) {
        { action ->
            val needed = buildList {
                if (!VoiceInput.hasPermission(context)) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) action() else {
                pending = action
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}

/** 每秒刷新一次的时长：之前录过的加上这次的 */
@Composable
private fun ElapsedText(interpreter: Interpreter, fontSize: Int, color: Color) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    // 读一下 tick，每秒重组一次
    val text = tick.let { formatDuration(InterpretSession.previousDuration + interpreter.elapsed / 1000.0) }
    Text(text, fontSize = fontSize.sp, color = color)
}

// 首页

/** 同声传译首页：开始区 + 传译记录 */
@Composable
fun InterpretHome(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: (String?) -> Unit) {
    val context = LocalContext.current
    SideEffect { InterpretSession.attach(context) }
    val withPermissions = rememberInterpretPermissions()
    val active = InterpretSession.isActive
    val start: (String?) -> Unit = { id -> if (active) onStart(id) else withPermissions { onStart(id) } }
    val module = AppModule.INTERPRET
    ModuleScroll(top) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(module.card).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    HomeChip(directionTitle(InterpretSession.defaultFromChinese), Icons.Rounded.ExpandMore, on = false) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(false, true).forEach { chinese ->
                            DropdownMenuItem(
                                text = { Text(directionOption(chinese)) },
                                leadingIcon = { if (InterpretSession.defaultFromChinese == chinese) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                                onClick = {
                                    InterpretSession.updateDefaultFromChinese(chinese)
                                    menu = false
                                },
                            )
                        }
                    }
                }
                HomeChip("耳机朗读", Icons.Rounded.Headphones, on = Prefs.interpreterSpeak) {
                    Prefs.updateInterpreterSpeak(!Prefs.interpreterSpeak)
                }
            }
            Row(
                Modifier.height(56.dp).clip(RoundedCornerShape(50)).background(Danger).clickable { start(null) }.padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (active) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, null, tint = Color.White)
                Text(if (active) "回到正在进行的传译" else "开始传译", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                "听讲座、开会时用：持续收音，自动断句，上面原文、下面译文。",
                fontSize = 13.sp, textAlign = TextAlign.Center, color = module.ink,
            )
        }

        ModuleCaption("传译记录")
        RecordList(
            ids = ModuleStore.interpretations.map { it.id },
            emptyText = "还没有传译记录",
            deleteTitle = { "删除“${ModuleStore.interpretation(it)?.title ?: ""}”？" },
            onDelete = { ModuleStore.deleteInterpretation(it) },
        ) { id ->
            ModuleStore.interpretation(id)?.let { record ->
                ModuleRow(
                    module, record.title,
                    meta = "${formatDate(record.updatedAt)} · ${record.lines.size} 句 · ${formatDuration(record.duration)}",
                    pill = "继续", onOpen = { onOpen(id) }, onPill = { start(id) },
                )
            }
        }
    }
}

@Composable
private fun HomeChip(title: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val ink = AppModule.INTERPRET.ink
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(50))
                .background(if (on) ink else Lx.colors.background.copy(alpha = 0.8f)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val color = if (on) Lx.colors.background else ink
            Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** 显示方式：对照、只看原文、只看译文 */
@Composable
private fun DisplayPicker(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Lx.colors.surface2).padding(2.dp),
    ) {
        listOf("对照", "原文", "译文").forEachIndexed { index, title ->
            val selected = InterpretSession.display == index
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Lx.colors.background else Color.Transparent)
                    .selectable(selected, role = Role.Tab) { InterpretSession.updateDisplay(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = Lx.colors.ink)
            }
        }
    }
}

// 记录全文

/** 一条传译记录的全文：对照、原文、译文三种显示，可以复制、导出、让 AI 总结要点、接着录 */
@Composable
fun InterpretRecordPage(id: String, onBack: () -> Unit, onContinue: () -> Unit) {
    val record = ModuleStore.interpretation(id)
    if (record == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val withPermissions = rememberInterpretPermissions()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<PendingDelete?>(null) }
    var summarizing by remember { mutableStateOf(false) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    val display = InterpretSession.display

    fun summarize() {
        if (!AISettings.isConfigured) {
            summaryError = "要先在设置里填写 AI 的 API Key。"
            return
        }
        summaryError = null
        summarizing = true
        scope.launch {
            try {
                Analytics.track(Analytics.Event.INTERPRET_SUMMARY)
                val response = InterpretAITasks.summarizeTranscript(record.lines, AISettings.currentConfig)
                ModuleStore.updateInterpretation(id) { it.copy(summary = response.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summaryError = e.message ?: e.toString()
            } finally {
                summarizing = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleTopBar(record.title, onBack) {
            Box {
                RoundIconButton(Icons.Rounded.MoreHoriz, "更多", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("改名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            menu = false
                            renameText = record.title
                            renaming = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) },
                        onClick = {
                            menu = false
                            deleting = PendingDelete("删除“${record.title}”？") {
                                ModuleStore.deleteInterpretation(id)
                                onBack()
                            }
                        },
                    )
                }
            }
        }
        Text(
            "${formatDate(record.createdAt)} · ${record.lines.size} 句 · ${formatDuration(record.duration)} · ${directionTitle(record.sourceIsChinese)}",
            fontSize = 13.sp, color = Lx.colors.ink3,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
        DisplayPicker(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 150.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    record.summary?.let { summary ->
                        item(key = "summary") {
                            SummaryCard(summary, onCopy = { clipboard.setText(AnnotatedString(summary)) })
                        }
                    }
                    items(record.lines, key = { it.id }) { line ->
                        Column(
                            Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (display != 2) {
                                Text(
                                    line.original, fontSize = if (display == 1) 17.sp else 14.sp,
                                    color = if (display == 1) Lx.colors.ink else Lx.colors.ink2,
                                )
                            }
                            if (display != 1) Text(line.translation, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Lx.colors.ink)
                        }
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Lx.colors.background.copy(alpha = 0.96f))
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summaryError?.let { Text(it, fontSize = 12.sp, color = Lx.colors.ai, textAlign = TextAlign.Center) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton("复制", Icons.Rounded.ContentCopy) { clipboard.setText(AnnotatedString(exportText(record))) }
                    BarButton("导出", Icons.Rounded.IosShare) { share(context, record) }
                    BarButton(if (summarizing) "总结中…" else "要点", Icons.Rounded.AutoAwesome, tint = Lx.colors.ai, enabled = !summarizing) { summarize() }
                    Row(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(Lx.colors.transcriptInk)
                            .clickable { if (InterpretSession.isActive) onContinue() else withPermissions(onContinue) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Rounded.Mic, null, tint = Lx.colors.background, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("继续", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Lx.colors.background)
                    }
                }
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改名") },
            text = { OutlinedTextField(renameText, { renameText = it }, placeholder = { Text("名称") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty()) ModuleStore.updateInterpretation(id) { it.copy(title = name) }
                    renaming = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    ConfirmDelete(deleting, onDismiss = { deleting = null })
}

private fun exportText(record: InterpretRecord): String =
    record.title + "\n\n" + record.lines.joinToString("\n\n") { it.original + "\n" + it.translation }

/** 导出：系统分享面板 */
private fun share(context: Context, record: InterpretRecord) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, record.title)
        putExtra(Intent.EXTRA_TEXT, exportText(record))
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

@Composable
private fun SummaryCard(summary: String, onCopy: () -> Unit) {
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(Lx.colors.aiSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Lx.colors.ai, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("要点", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Lx.colors.ai)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCopy).semantics { contentDescription = "复制要点" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ContentCopy, null, tint = Lx.colors.ink2, modifier = Modifier.size(17.dp)) }
        }
        Text(summary, fontSize = 15.sp, color = Lx.colors.ink)
    }
}

@Composable
private fun BarButton(title: String, icon: ImageVector, tint: Color = Lx.colors.ink, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(Lx.colors.surface)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp).alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

// 进行中的传译

/**
 * 进行中的传译（全屏）：滚动显示双语字幕，正在说的那句是蓝底。
 * 收起或按返回键不等于结束：传译在后台继续，别的页面上方显示小提示条；点红色按钮才结束并保存
 */
@Composable
fun InterpreterScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val interpreter = InterpretSession.interpreter
    if (interpreter == null) {
        // 传译已经结束（比如在通知里点了结束）：把这一页关掉
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val withPermissions = rememberInterpretPermissions()

    LaunchedEffect(interpreter) { InterpretSession.ensureStarted(context) }
    DisposableEffect(Unit) {
        InterpretSession.presented = true
        Analytics.pageStart("同声传译")
        onDispose {
            Analytics.pageEnd("同声传译")
            if (InterpretSession.presented) InterpretSession.minimize()
        }
    }
    // 收音时屏幕不自动熄灭
    val view = LocalView.current
    val running = interpreter.state == Interpreter.State.Running
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    fun minimize() {
        // 还没开始或者出错了：没什么可在后台继续的，直接结束
        if (interpreter.isActive || interpreter.state is Interpreter.State.Preparing) {
            InterpretSession.minimize()
            onClose()
        } else {
            InterpretSession.finish(onClose)
        }
    }
    BackHandler { minimize() }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Lx.colors.wash, 0.35f to Lx.colors.background))
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        TopBar(interpreter, onMinimize = { minimize() })
        DisplayPicker(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Subtitles(interpreter)
            StatusOverlay(interpreter, onRetry = { withPermissions { InterpretSession.retry(context) } })
        }
        BottomBar(interpreter, onFinish = { InterpretSession.finish(onClose) })
    }
}

@Composable
private fun TopBar(interpreter: Interpreter, onMinimize: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoundIconButton(Icons.Rounded.KeyboardArrowDown, "收起，传译在后台继续", onMinimize)
        // 点开下拉选语言，不会一碰就切换
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(Lx.colors.surface)
                    .clickable { menu = true }.semantics { contentDescription = "听哪种语言：点开选择" }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (interpreter.state == Interpreter.State.Running) Box(Modifier.size(8.dp).clip(CircleShape).background(Danger))
                Text(
                    "同声传译 · " + directionTitle(interpreter.sourceIsChinese), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Lx.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Rounded.ExpandMore, null, tint = Lx.colors.ink3, modifier = Modifier.size(18.dp))
                ElapsedText(interpreter, 15, Lx.colors.ink3)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(false, true).forEach { chinese ->
                    DropdownMenuItem(
                        text = { Text(directionOption(chinese)) },
                        leadingIcon = { if (interpreter.sourceIsChinese == chinese) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = {
                            menu = false
                            // 选了和现在不同的语言，就中途换方向（前面的字幕保留，后面按新语言识别）
                            InterpretSession.switchDirection(chinese)
                        },
                    )
                }
            }
        }
        val speak = interpreter.speakTranslations
        RoundIconButton(
            if (speak) Icons.Rounded.Headphones else Icons.Rounded.VolumeOff,
            if (speak) "关闭耳机朗读" else "用耳机朗读译文",
            {
                interpreter.speakTranslations = !speak
                if (speak) Speaker.stop()
            },
            tint = if (speak) Lx.colors.accent else Lx.colors.ink,
        )
    }
}

@Composable
private fun Subtitles(interpreter: Interpreter) {
    val display = InterpretSession.display
    val previous = InterpretSession.previous
    val segments = interpreter.segments
    val live = interpreter.live
    val liveTranslation = interpreter.liveTranslation
    val list = rememberLazyListState()
    val continuedAt = remember(InterpretSession.continuedAt) { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(InterpretSession.continuedAt)) }

    LaunchedEffect(segments.size) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    LaunchedEffect(live, liveTranslation) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.scrollToItem(count - 1)
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(12.dp)) {
        // 继续录：先显示之前录过的字幕，中间用一条分隔线隔开
        items(previous, key = { "p" + it.id }) { line ->
            Subtitle(line.original, line.translation, display, modifier = Modifier.alpha(0.55f))
        }
        if (previous.isNotEmpty()) {
            item(key = "separator") {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = Lx.colors.line)
                    Text(
                        "继续 · $continuedAt", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.transcriptInk,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    HorizontalDivider(Modifier.weight(1f), color = Lx.colors.line)
                }
            }
        }
        items(segments, key = { it.id }) { segment ->
            Subtitle(segment.original, segment.translation, display)
        }
        if (live.isNotEmpty()) {
            // 还没说完的那句：原文和边说边翻的译文一起往下长
            item(key = "live") {
                Subtitle(
                    "$live…",
                    if (liveTranslation.isEmpty()) (if (display == 2) "…" else null) else "$liveTranslation…",
                    display, pending = false,
                    modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Lx.colors.accentSoft).padding(vertical = 4.dp),
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
    }
}

/** 一条字幕：按显示方式只显示原文、只显示译文，或者对照（原文小字在上、译文在下） */
@Composable
private fun Subtitle(original: String, translation: String?, display: Int, pending: Boolean = true, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (display != 2) {
            Text(
                original, fontSize = if (display == 1) 17.sp else 14.sp, lineHeight = if (display == 1) 24.sp else 20.sp,
                color = if (display == 1) Lx.colors.ink else Lx.colors.ink2,
            )
        }
        if (display != 1) {
            if (translation != null) {
                Text(translation, fontSize = 16.sp, lineHeight = 23.sp, color = Lx.colors.ink)
            } else if (pending) {
                CircularProgressIndicator(Modifier.padding(vertical = 3.dp).size(12.dp), strokeWidth = 1.5.dp, color = Lx.colors.ink3)
            }
        }
    }
}

@Composable
private fun StatusOverlay(interpreter: Interpreter, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = interpreter.state) {
            is Interpreter.State.Preparing -> Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Lx.colors.surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(state.message, color = Lx.colors.ink)
            }
            is Interpreter.State.Failed -> Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Lx.colors.ink2)
                    Text(state.message, color = Lx.colors.ink, textAlign = TextAlign.Center)
                }
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Lx.colors.accent)) { Text("重试") }
            }
            else -> {
                if (InterpretSession.previous.isEmpty() && interpreter.segments.isEmpty() && interpreter.live.isEmpty() &&
                    interpreter.state == Interpreter.State.Running
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Lx.colors.accent, modifier = Modifier.size(40.dp))
                        Text(
                            if (interpreter.sourceIsChinese) "正在听中文，说完一句就会翻译" else "正在听英语，说完一句就会翻译",
                            color = Lx.colors.ink3,
                        )
                        Text(
                            (if (interpreter.engineName.isEmpty()) "" else interpreter.engineName + " · ") +
                                (if (InterpretSession.translatorOnDevice) "本机翻译" else "在线翻译") + " · 录音不上传",
                            fontSize = 12.sp, color = Lx.colors.ink3.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(interpreter: Interpreter, onFinish: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        VoiceWave(interpreter.levels)
        Spacer(Modifier.weight(1f))
        val state = interpreter.state
        if (state == Interpreter.State.Running || state == Interpreter.State.Paused) {
            val paused = state == Interpreter.State.Paused
            RoundIconButton(
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "继续" else "暂停",
                { if (paused) interpreter.resume() else interpreter.pause() },
            )
        }
        Box(
            Modifier.size(64.dp).shadow(8.dp, CircleShape, ambientColor = Danger, spotColor = Danger).clip(CircleShape).background(Danger)
                .clickable(enabled = !InterpretSession.finishing, onClick = onFinish).semantics { contentDescription = "结束并保存" },
            contentAlignment = Alignment.Center,
        ) {
            if (InterpretSession.finishing) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
            } else {
                Icon(Icons.Rounded.Stop, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** 声波：最近一段的音量 */
@Composable
private fun VoiceWave(levels: List<Float>) {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
        levels.forEach { level ->
            Box(Modifier.width(3.dp).height((4 + level * 22).dp).clip(RoundedCornerShape(50)).background(Lx.colors.accent.copy(alpha = 0.85f)))
        }
    }
}

// 收起后的小提示条

/** 传译收起到后台时，其他模块页面顶上的小提示条：正在传译、时长、最新一句；点一下回到传译页。没在传译时不显示 */
@Composable
fun InterpretMiniBar(onOpen: () -> Unit) {
    val context = LocalContext.current
    SideEffect { InterpretSession.attach(context) }
    val interpreter = InterpretSession.interpreter ?: return
    if (InterpretSession.presented) return
    val paused = interpreter.state == Interpreter.State.Paused
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 50.dp).shadow(6.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50)).background(Lx.colors.surface).clickable(onClick = onOpen)
            .semantics { contentDescription = "同声传译正在进行，点按回到传译" }.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (paused) Paused else Danger))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (paused) "同声传译 · 已暂停" else "同声传译中", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Lx.colors.ink)
                ElapsedText(interpreter, 12, Lx.colors.ink3)
            }
            Text(InterpretSession.latestLine(interpreter), fontSize = 13.sp, color = Lx.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Lx.colors.ink3, modifier = Modifier.size(18.dp))
    }
}
