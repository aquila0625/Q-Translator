package com.yishulabs.qtranslator.ui.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.conversation.HistoryStore
import com.yishulabs.qtranslator.core.Phrase
import com.yishulabs.qtranslator.modules.PracticeLine
import com.yishulabs.qtranslator.modules.PracticeRecord
import com.yishulabs.qtranslator.ui.Lx

// 练习里共用的部件：我说的那句和下面的“更地道”，以及练习小结。对应苹果版 CorrectionNote / PracticeSummary

/** 我说的那句下面：更地道的说法和原因 */
@Composable
internal fun CorrectionNote(better: String, reason: String?) {
    val colors = Lx.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.transcriptCard).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("更地道：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.transcriptInk)
        SelectionContainer { Text(better, fontSize = 15.sp, color = colors.ink) }
        if (!reason.isNullOrEmpty()) Text(reason, fontSize = 12.sp, color = colors.ink3)
    }
}

/** 我说的话：靠右的蓝色气泡，下面跟着“更地道” */
@Composable
internal fun PracticeMine(line: PracticeLine) {
    val colors = Lx.colors
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Text(
                line.text, fontSize = 17.sp, color = colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(colors.accent).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        line.better?.let { CorrectionNote(it, line.reason) }
    }
}

/** 练习小结：说了几句、几处可以更地道、学到几个新说法；新说法可以加入生词本 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PracticeSummarySheet(record: PracticeRecord, onAgain: () -> Unit, onDone: () -> Unit) {
    val colors = Lx.colors
    val corrected = record.lines.filter { it.better != null }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("练习小结", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryStat(record.lines.count { it.isMine }, "句是我说的", colors.ink)
                SummaryStat(corrected.size, "处可以更地道", colors.ai)
                SummaryStat(record.phrases.size, "个新说法", colors.sentenceInk)
            }
            if (corrected.isNotEmpty()) {
                ModuleCaption("更地道的说法")
                corrected.forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line.text, fontSize = 13.sp, color = colors.ink3, textDecoration = TextDecoration.LineThrough)
                        CorrectionNote(line.better ?: "", line.reason)
                    }
                }
            }
            if (record.phrases.isNotEmpty()) {
                ModuleCaption("新说法")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
                    record.phrases.forEachIndexed { index, phrase ->
                        val starred = HistoryStore.isStarred(phrase.key)
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(phrase.key, fontSize = 16.sp, color = colors.ink, modifier = Modifier.weight(1f))
                            Text(phrase.value, fontSize = 13.sp, color = colors.ink3, modifier = Modifier.padding(start = 8.dp).widthIn(max = 150.dp))
                            Icon(
                                if (starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                if (starred) "从生词本移除" else "加入生词本", tint = if (starred) colors.ai else colors.ink3,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { togglePhrase(phrase) }.padding(10.dp),
                            )
                        }
                        if (index < record.phrases.lastIndex) HorizontalDivider(Modifier.padding(start = 14.dp), color = colors.line)
                    }
                }
                val allStarred = record.phrases.all { HistoryStore.isStarred(it.key) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(50))
                        .background(if (allStarred) colors.ink3.copy(alpha = 0.5f) else colors.accent)
                        .clickable(enabled = !allStarred) { record.phrases.filter { !HistoryStore.isStarred(it.key) }.forEach(::togglePhrase) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (allStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (allStarred) "已全部加入生词本" else "全部加入生词本", fontWeight = FontWeight.SemiBold, color = colors.onAccent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryButton("再练一次", onAgain, showIcon = true)
                SummaryButton("完成", onDone, showIcon = false)
            }
            Text("这次练习已保存在练习记录里。", fontSize = 12.sp, color = colors.ink3.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** 加入或移出生词本：还没查过的说法先记进历史，带上中文意思 */
private fun togglePhrase(phrase: Phrase) {
    if (HistoryStore.items.none { it.text == phrase.key }) HistoryStore.add(phrase.key, phrase.value)
    HistoryStore.toggleStar(phrase.key, phrase.value)
}

@Composable
private fun RowScope.SummaryStat(value: Int, title: String, color: Color) {
    Column(
        Modifier.weight(1f).heightIn(min = 86.dp).clip(RoundedCornerShape(16.dp)).background(Lx.colors.surface).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("$value", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text(title, fontSize = 12.sp, color = Lx.colors.ink3)
    }
}

@Composable
private fun RowScope.SummaryButton(title: String, onClick: () -> Unit, showIcon: Boolean) {
    Row(
        Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(50)).background(Lx.colors.surface).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Icon(Icons.Rounded.Refresh, null, tint = Lx.colors.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(title, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink)
    }
}
