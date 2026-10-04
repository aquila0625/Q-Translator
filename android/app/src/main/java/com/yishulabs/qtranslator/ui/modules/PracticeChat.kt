package com.yishulabs.qtranslator.ui.modules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.ai.PracticeAITasks
import com.yishulabs.qtranslator.analytics.AnalyticsPage
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.modules.PracticeLine
import com.yishulabs.qtranslator.modules.PracticeRecord
import com.yishulabs.qtranslator.ui.Lx

// 进行中的场景练习：先选场景（没有 seed 时），然后和 AI 对话，结束时显示小结。对应苹果版 PracticeView.swift

/** seed：直接练这个场景（模块首页点了某个场景，或者“再练一次”）；null 时先选场景 */
@Composable
internal fun PracticeActivity(seed: PracticeRecord?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { PracticeSession(seed, scope) }
    AnalyticsPage("场景练习")
    LaunchedEffect(Unit) { session.openIfNeeded() }
    DisposableEffect(Unit) {
        onDispose {
            if (VoiceInput.isListening) VoiceInput.cancel()
            Speaker.stop()
        }
    }
    fun finish() {
        if (VoiceInput.isListening) VoiceInput.cancel()
        if (!session.finish()) onClose()
    }
    BackHandler { if (session.showSummary) onClose() else if (session.record == null) onClose() else finish() }

    Box(Modifier.fillMaxSize().background(Lx.colors.background).statusBarsPadding()) {
        val record = session.record
        if (record == null) PracticeSetup(onClose) { scenario, role -> session.begin(scenario, role, PracticePrefs.level) }
        else PracticeChatView(session, record, onFinish = ::finish)
        if (session.showSummary && record != null) {
            Box(Modifier.fillMaxSize().background(Lx.colors.background)) {
                PracticeSummarySheet(record, onAgain = { session.again() }, onDone = onClose)
            }
        }
    }
}

/** 选场景：难度、预设场景、自己描述 */
@Composable
private fun PracticeSetup(onClose: () -> Unit, onBegin: (scenario: String, role: String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    fun beginCustom() {
        if (custom.isNotBlank()) onBegin(custom.trim(), "")
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { TopIcon(Icons.Rounded.Close, "关闭", onClick = onClose) }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("英语场景练习", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Lx.colors.ink)
                    Text(
                        "选一个场景，AI 扮演对方，你用英语回答，可以说也可以打字。说得不地道的地方会在下面给出更好的说法，不打断对话。",
                        fontSize = 15.sp, color = Lx.colors.ink2,
                    )
                }
                PracticeLevelPicker(PracticePrefs.level) { PracticePrefs.updateLevel(it) }
                PresetGrid(PracticePreset.all, showCustom = false, onPick = { onBegin(it.scenario, it.role) }, onCustom = {}, detailed = true)
                Text("自己描述一个场景", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        custom, { custom = it }, placeholder = { Text("例如：在咖啡店和咖啡师聊天") }, maxLines = 3,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { beginCustom() }),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (custom.isBlank()) Lx.colors.ink3.copy(alpha = 0.4f) else Lx.colors.practiceInk)
                            .clickable(enabled = custom.isNotBlank(), onClick = ::beginCustom).semantics { contentDescription = "开始练习" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Lx.colors.onAccent) }
                }
            }
        }
    }
}

/** 对话：顶栏（结束、场景、中文意思、朗读和语速）、对话气泡、输入区 */
@Composable
private fun PracticeChatView(session: PracticeSession, record: PracticeRecord, onFinish: () -> Unit) {
    val colors = Lx.colors
    val list = rememberLazyListState()
    val itemCount = record.lines.size + (if (session.thinking) 1 else 0) + (if (session.error != null) 1 else 0)
    LaunchedEffect(itemCount, PracticePrefs.showChinese, VoiceInput.isListening) {
        if (itemCount > 0) list.animateScrollToItem(itemCount - 1)
    }
    LaunchedEffect(PracticePrefs.speak) { if (!PracticePrefs.speak) Speaker.stop() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TopIcon(Icons.Rounded.Close, "结束练习", onClick = onFinish)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "练习：" + record.scenario.substringBefore("："), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (record.role.isEmpty()) "AI 正在进入角色" else "AI 是${record.role}") + " · " + PracticeAITasks.levelTitle(record.level),
                    fontSize = 12.sp, color = colors.ink3,
                )
            }
            TopIcon(
                Icons.Rounded.Translate, if (PracticePrefs.showChinese) "隐藏中文意思" else "显示中文意思",
                tint = if (PracticePrefs.showChinese) colors.practiceInk else colors.ink,
            ) {
                PracticePrefs.updateShowChinese(!PracticePrefs.showChinese)
                session.revealed = emptySet()
            }
            SpeakMenu()
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(record.lines, key = { it.id }) { line ->
                if (line.isMine) PracticeMine(line) else Partner(line, session)
            }
            if (session.thinking) item("thinking") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (record.lines.isEmpty()) "AI 正在准备开场…" else "对方正在回复…", fontSize = 13.sp, color = colors.ink3)
                }
            }
            session.error?.let { error ->
                item("error") {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { session.respond() }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = colors.ai, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$error 点这里重试", fontSize = 13.sp, color = colors.ai)
                    }
                }
            }
        }
        PracticeComposer(session)
    }
}

/** AI 说的话：点一下显示或收起中文意思，旁边可以朗读 */
@Composable
private fun Partner(line: PracticeLine, session: PracticeSession) {
    val colors = Lx.colors
    val showing = PracticePrefs.showChinese != (line.id in session.revealed)
    Row(Modifier.fillMaxWidth().padding(end = 40.dp), verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier.weight(1f, fill = false).clip(RoundedCornerShape(18.dp)).background(colors.surface)
                .clickable { session.toggleReveal(line.id) }.padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = if (showing) "收起中文意思" else "显示中文意思" },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(line.text, fontSize = 17.sp, color = colors.ink)
            if (showing && !line.chinese.isNullOrEmpty()) Text(line.chinese, fontSize = 15.sp, color = colors.ink3)
        }
        val speech = Speech.english(line.text)
        TopIcon(if (Speaker.playing == speech) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, "朗读", size = 32, tint = colors.ink3) {
            Speaker.toggle(speech)
        }
    }
}

/** 朗读开关和语速 */
@Composable
private fun SpeakMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        TopIcon(
            if (PracticePrefs.speak) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "朗读和语速",
        ) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("自动朗读对方的话") },
                trailingIcon = { Switch(PracticePrefs.speak, onCheckedChange = { PracticePrefs.updateSpeak(it) }) },
                onClick = { PracticePrefs.updateSpeak(!PracticePrefs.speak) },
            )
            Speaker.speeds.forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            Speaker.speedLabel(speed),
                            fontWeight = if (Prefs.playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                            color = if (Prefs.playbackSpeed == speed) Lx.colors.accent else Lx.colors.ink,
                        )
                    },
                    onClick = { Prefs.updatePlaybackSpeed(speed); open = false },
                )
            }
        }
    }
}

@Composable
internal fun TopIcon(
    icon: ImageVector, label: String, size: Int = 44,
    tint: androidx.compose.ui.graphics.Color = Lx.colors.ink, onClick: () -> Unit,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.45).dp)) }
}
