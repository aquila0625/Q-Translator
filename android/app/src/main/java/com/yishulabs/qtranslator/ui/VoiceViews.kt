package com.yishulabs.qtranslator.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.cancelVoice
import com.yishulabs.qtranslator.conversation.finishVoice
import com.yishulabs.qtranslator.core.AudioReplay
import com.yishulabs.qtranslator.core.VoiceInput
import kotlinx.coroutines.delay

// 语音输入的界面部件，对应苹果版 VoiceViews.swift：麦克风按钮、正在听的面板、出错提示、回放原声按钮。

private val Recording = Color(0xFFE5372B)

/** 麦克风按钮：输入框没有内容时代替发送按钮 */
@Composable
fun MicButton(size: Int = 40, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 4.dp).size(size.dp).clip(CircleShape).background(Lx.colors.accentSoft)
            .clickable(onClick = onClick).semantics { contentDescription = "语音输入" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, null, tint = Lx.colors.accent, modifier = Modifier.size((size * 0.5).dp))
    }
}

/** 正在听：实时显示识别出的文字（还可能变的部分是浅色），左下角是识别语言（点一下中英切换）和声波，右边红色按钮结束，右上角的叉取消 */
@Composable
fun VoiceListeningPanel(controller: ConversationController) {
    val colors = Lx.colors
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Recording))
            Spacer(Modifier.width(8.dp))
            Text("正在听 · ${VoiceInput.language.title}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.accent)
            Spacer(Modifier.weight(1f))
            VoiceInput.startedAt?.let { ElapsedTime(it) }
            Box(
                Modifier.size(36.dp, 32.dp).clip(RoundedCornerShape(8.dp)).clickable { controller.cancelVoice() }
                    .semantics { contentDescription = "取消语音输入" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, null, tint = colors.ink3, modifier = Modifier.size(18.dp)) }
        }
        val final = VoiceInput.finalText
        val volatile = VoiceInput.volatileText
        if (final.isBlank() && volatile.isBlank()) {
            Text("请说话…", fontSize = 17.sp, color = colors.ink3)
        } else {
            Text(
                buildAnnotatedString {
                    append(final)
                    if (final.isNotEmpty() && volatile.isNotEmpty() && VoiceInput.language == VoiceInput.Language.ENGLISH) append(" ")
                    withStyle(SpanStyle(color = colors.ink3)) { append(volatile) }
                },
                fontSize = 17.sp, color = colors.ink, maxLines = 6,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(colors.accentSoft)
                    .clickable { VoiceInput.switchLanguage() }.padding(horizontal = 11.dp)
                    .semantics { contentDescription = "识别语言：${VoiceInput.language.title}，点按切换" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Language, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (VoiceInput.language == VoiceInput.Language.ENGLISH) "英语 ⇄ 中文" else "中文 ⇄ 英语",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent,
                )
            }
            Spacer(Modifier.width(10.dp))
            VoiceWave(VoiceInput.levels, Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clickable { controller.finishVoice() }.semantics { contentDescription = "说完了" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Recording), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Stop, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** 从开始到现在过了多久（0:12） */
@Composable
private fun ElapsedTime(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Text(formatDuration((now - startedAt) / 1000.0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink3)
}

/** 声波：最近一段时间的音量 */
@Composable
fun VoiceWave(levels: List<Float>, modifier: Modifier = Modifier) {
    Row(modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        levels.forEach { level ->
            val height by animateDpAsState((4 + level * 22).dp, label = "wave")
            Box(Modifier.width(3.dp).height(height).clip(RoundedCornerShape(50)).background(Lx.colors.accent.copy(alpha = 0.85f)))
        }
    }
}

/** 语音出错时的提示（例如没有权限），点一下关掉 */
@Composable
fun VoiceErrorBanner() {
    val failed = VoiceInput.state as? VoiceInput.State.Failed ?: return
    Row(
        Modifier.fillMaxWidth().clickable { VoiceInput.clearError() }.padding(start = 14.dp, end = 14.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MicOff, null, tint = Lx.colors.ai, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(failed.message, fontSize = 13.sp, color = Lx.colors.ai)
    }
}

/** 一轮里的“回放原声”：语音输入时录下的声音 */
@Composable
fun AudioReplayButton(fileName: String, duration: Double?) {
    val playing = AudioReplay.playing == fileName
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).clickable { AudioReplay.toggle(fileName) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.GraphicEq, null, tint = Lx.colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (playing) "停止" else "回放原声" + (duration?.let { " " + formatDuration(it) } ?: ""),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.accent,
        )
    }
}

/** 秒数写成 0:12 */
fun formatDuration(seconds: Double): String {
    val s = Math.round(seconds).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
