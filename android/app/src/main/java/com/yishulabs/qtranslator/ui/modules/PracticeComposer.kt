package com.yishulabs.qtranslator.ui.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import java.io.File
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.ui.Lx
import com.yishulabs.qtranslator.ui.MicButton
import com.yishulabs.qtranslator.ui.VoiceErrorBanner
import com.yishulabs.qtranslator.ui.VoiceWave
import kotlinx.coroutines.delay

// 场景练习的输入区：打字、按住说（语音输入），或者语音聊天（像打电话一样，不用点发送）。对应苹果版 PracticeView.swift 的 composer

/** 说完的话发出去；练习里用不到原声（filesDir/audio 下），说完就删 */
private fun finishVoice(session: PracticeSession, context: Context) {
    val result = VoiceInput.stop()
    result.audio?.let { File(File(context.filesDir, "audio"), it).delete() }
    if (result.text.isNotBlank()) session.send(result.text, if (PracticePrefs.handsFree) "voice_chat" else "voice")
}

@Composable
internal fun PracticeComposer(session: PracticeSession) {
    val context = LocalContext.current
    val withMic = rememberMicPermission()
    val record = session.record

    // 语音聊天时，现在是不是该轮到我说：对方说完了、没在想、没在读
    val shouldListen = PracticePrefs.handsFree && record != null && !session.showSummary && !session.thinking &&
        session.error == null && Speaker.playing == null && !VoiceInput.isListening &&
        record.lines.lastOrNull()?.isMine == false && VoiceInput.state !is VoiceInput.State.Failed
    LaunchedEffect(shouldListen) {
        if (!shouldListen) return@LaunchedEffect
        delay(350)
        withMic { VoiceInput.start(VoiceInput.Language.ENGLISH) }
    }
    // 语音聊天：说完停顿一会儿自动发送
    LaunchedEffect(PracticePrefs.handsFree && VoiceInput.isListening) {
        if (!(PracticePrefs.handsFree && VoiceInput.isListening)) return@LaunchedEffect
        var last = VoiceInput.text
        var since = System.currentTimeMillis()
        while (VoiceInput.isListening) {
            delay(250)
            val now = VoiceInput.text
            if (now != last) {
                last = now
                since = System.currentTimeMillis()
            } else if (last.isNotEmpty() && System.currentTimeMillis() - since > 1600) {
                finishVoice(session, context)
                break
            }
        }
    }

    Surface(
        Modifier.widthIn(max = 680.dp).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp), color = Lx.colors.background, shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceErrorBanner()
            when {
                PracticePrefs.handsFree -> HandsFreePanel(session)
                VoiceInput.isListening -> ListeningRow(session)
                else -> TypingRow(session, withMic)
            }
        }
    }
}

@Composable
private fun TypingRow(session: PracticeSession, withMic: (() -> Unit) -> Unit) {
    val colors = Lx.colors
    val focus = LocalFocusManager.current
    var input by remember { mutableStateOf("") }
    fun sendTyped() {
        if (input.isBlank() || session.thinking) return
        val text = input.trim()
        input = ""
        session.send(text, "text")
    }
    Row(verticalAlignment = Alignment.Bottom) {
        CircleIcon(Icons.Rounded.GraphicEq, "切换到语音聊天", colors.practiceInk, colors.practiceCard) {
            focus.clearFocus()
            PracticePrefs.updateHandsFree(true)
            Analytics.track(Analytics.Event.PRACTICE_HANDS_FREE, mapOf("on" to "yes"))
        }
        Box(Modifier.weight(1f).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (input.isEmpty()) Text("用英语回答，也可以打中文", fontSize = 16.sp, color = colors.ink3)
            BasicTextField(
                input, { input = it }, maxLines = 4, cursorBrush = SolidColor(colors.accent),
                textStyle = TextStyle(fontSize = 16.sp, color = colors.ink), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendTyped() }),
            )
        }
        if (input.isBlank()) {
            MicButton {
                if (session.thinking) return@MicButton
                focus.clearFocus()
                Speaker.stop()
                withMic { VoiceInput.start(VoiceInput.Language.ENGLISH) }
            }
        } else {
            CircleIcon(
                Icons.Rounded.ArrowUpward, "发送", colors.onAccent,
                if (session.thinking) colors.ink3.copy(alpha = 0.4f) else colors.accent, enabled = !session.thinking,
            ) { sendTyped() }
        }
    }
}

/** 按了麦克风正在听：取消、声波和识别出的文字、发送 */
@Composable
private fun ListeningRow(session: PracticeSession) {
    val colors = Lx.colors
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIcon(Icons.Rounded.Close, "取消", colors.ink, colors.ink3.copy(alpha = 0.15f)) { VoiceInput.cancel() }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VoiceWave(VoiceInput.levels.takeLast(16))
            val text = VoiceInput.text
            Text(text.ifEmpty { "请说英语…" }, fontSize = 15.sp, color = if (text.isEmpty()) colors.ink3 else colors.ink, maxLines = 3)
        }
        CircleIcon(Icons.Rounded.ArrowUpward, "说完了，发送", colors.onAccent, colors.accent) { finishVoice(session, context) }
    }
}

/** 语音聊天：不用点发送，显示现在轮到谁 */
@Composable
private fun HandsFreePanel(session: PracticeSession) {
    val colors = Lx.colors
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIcon(Icons.Rounded.Keyboard, "换回打字", colors.ink, colors.ink3.copy(alpha = 0.15f)) {
            PracticePrefs.updateHandsFree(false)
            Analytics.track(Analytics.Event.PRACTICE_HANDS_FREE, mapOf("on" to "no"))
            if (VoiceInput.isListening) VoiceInput.cancel()
        }
        Column(Modifier.weight(1f).heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
            when {
                VoiceInput.isListening -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("轮到你说了", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.practiceInk)
                        Spacer(Modifier.width(8.dp))
                        VoiceWave(VoiceInput.levels.takeLast(12))
                    }
                    val text = VoiceInput.text
                    Text(
                        text.ifEmpty { "说英语，说完停一下会自动发送" }, fontSize = 15.sp,
                        color = if (text.isEmpty()) colors.ink3 else colors.ink, maxLines = 3,
                    )
                }
                session.thinking -> Text("对方在想…", fontSize = 15.sp, color = colors.ink3)
                Speaker.playing != null -> Text("对方正在说…", fontSize = 15.sp, color = colors.ink3)
                VoiceInput.state is VoiceInput.State.Failed -> Text("没法开始听，换回打字试试", fontSize = 15.sp, color = colors.ai)
                else -> Text("语音聊天", fontSize = 15.sp, color = colors.ink3)
            }
        }
        if (VoiceInput.isListening) {
            val empty = VoiceInput.text.isEmpty()
            CircleIcon(
                Icons.Rounded.ArrowUpward, "现在发送", colors.onAccent,
                if (empty) colors.ink3.copy(alpha = 0.4f) else colors.accent, enabled = !empty,
            ) { finishVoice(session, context) }
        } else if (Speaker.playing != null) {
            CircleIcon(Icons.Rounded.SkipNext, "跳过，直接轮到我说", colors.ink, colors.ink3.copy(alpha = 0.15f)) { Speaker.stop() }
        }
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, label: String, tint: Color, background: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(background).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
    }
}
