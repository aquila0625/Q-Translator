package com.yishulabs.qtranslator.ui.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.modules.ModuleStore
import com.yishulabs.qtranslator.modules.PracticeLine
import com.yishulabs.qtranslator.modules.PracticeRecord
import com.yishulabs.qtranslator.ui.Lx

// 一次练习的回看：对话和每句的“更地道”，右上角看小结（新说法可以加入生词本）。对应苹果版 PracticeRecordPage

@Composable
internal fun PracticeRecordView(id: String, onBack: () -> Unit, onAgain: (PracticeRecord) -> Unit) {
    val entry = ModuleStore.practice(id)
    if (entry == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val record = entry.record
    val colors = Lx.colors
    var menu by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }

    Column(Modifier.fillMaxSize()) {
        ModuleTopBar(record.title(), onBack) {
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(colors.background.copy(alpha = 0.9f)).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MoreHoriz, "更多", tint = colors.ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("小结和新说法") }, leadingIcon = { Icon(Icons.Rounded.Star, null) },
                        onClick = { menu = false; showSummary = true },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFE5372B)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = androidx.compose.ui.graphics.Color(0xFFE5372B)) },
                        onClick = {
                            menu = false
                            pending = PendingDelete("删除这次练习？") {
                                ModuleStore.deletePractice(id)
                                onBack()
                            }
                        },
                    )
                }
            }
        }
        Text(record.scenario, fontSize = 13.sp, color = colors.ink3, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                record.lines.forEach { line -> if (line.isMine) PracticeMine(line) else RecordPartner(line) }
            }
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(bottom = 12.dp).height(48.dp)
                .clip(RoundedCornerShape(50)).background(colors.practiceInk).clickable { onAgain(record.fresh()) }.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = colors.background, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("再练一次", fontWeight = FontWeight.SemiBold, color = colors.background)
        }
    }
    if (showSummary) {
        PracticeSummarySheet(record, onAgain = { showSummary = false; onAgain(record.fresh()) }, onDone = { showSummary = false })
    }
    ConfirmDelete(pending, onDismiss = { pending = null })
}

/** 回看时对方说的话：中文意思直接显示 */
@Composable
private fun RecordPartner(line: PracticeLine) {
    val colors = Lx.colors
    Row(Modifier.fillMaxWidth().padding(end = 40.dp)) {
        SelectionContainer {
            Column(
                Modifier.clip(RoundedCornerShape(18.dp)).background(colors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(line.text, fontSize = 17.sp, color = colors.ink)
                if (!line.chinese.isNullOrEmpty()) Text(line.chinese, fontSize = 15.sp, color = colors.ink3)
            }
        }
    }
}
