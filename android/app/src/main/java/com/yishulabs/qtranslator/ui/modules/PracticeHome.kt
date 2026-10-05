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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.PracticeAITasks
import com.yishulabs.qtranslator.modules.ModuleStore
import com.yishulabs.qtranslator.modules.PracticeRecord
import com.yishulabs.qtranslator.ui.Lx
import com.yishulabs.qtranslator.ui.SettingsSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 场景练习首页：AI Key 提示、本周统计、难度、场景、练习记录。对应苹果版 ModuleViews.swift 的 PracticeHome

internal fun practiceTime(time: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))

@Composable
internal fun PracticeHomeView(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: (PracticeRecord?) -> Unit) {
    val colors = Lx.colors
    var showAll by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val week = ModuleStore.practicesThisWeek
    val level = PracticePrefs.level

    ModuleScroll(top) {
        if (!AISettings.isConfigured) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(16.dp)).background(colors.aiSoft)
                    .clickable { showSettings = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = colors.ai, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("场景练习要用 AI：先去设置里填写 API Key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ai)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeStat("${week.size}", "本周练习", colors.ink)
            PracticeStat("${week.sumOf { e -> e.record.lines.count { it.better != null } }}", "处改得更地道", colors.ai)
            PracticeStat("${week.sumOf { it.record.phrases.size }}", "个新说法", colors.sentenceInk)
        }
        PracticeLevelPicker(level) { PracticePrefs.updateLevel(it) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ModuleCaption("选一个场景")
            Spacer(Modifier.weight(1f))
            Text(
                if (showAll) "收起" else "全部 ${PracticePreset.all.size} 个 · 自己描述",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.practiceInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showAll = !showAll }.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        val presets = if (showAll) PracticePreset.all else PracticePreset.all.take(4)
        PresetGrid(presets, showCustom = showAll, onPick = { onStart(PracticeRecord(it.scenario, it.role, level)) }, onCustom = { onStart(null) })

        ModuleCaption("练习记录")
        RecordList(
            ids = ModuleStore.practices.map { it.id }, emptyText = "还没有练习记录",
            deleteTitle = { "删除这次练习？" }, onDelete = { ModuleStore.deletePractice(it) },
        ) { id ->
            ModuleStore.practice(id)?.let { entry ->
                val record = entry.record
                ModuleRow(
                    com.yishulabs.qtranslator.modules.AppModule.PRACTICE, record.title(),
                    "${practiceTime(entry.createdAt)} · ${record.lines.size} 句 · ${record.lines.count { it.better != null }} 处更地道",
                    "再练", onOpen = { onOpen(id) }, onPill = { onStart(record.fresh()) },
                )
            }
        }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false }, startAtAI = true)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PracticeStat(value: String, title: String, color: Color) {
    Column(
        Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Lx.colors.surface).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(title, fontSize = 11.sp, color = Lx.colors.ink3)
    }
}

/** 难度：初级、中级、高级 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PracticeLevelPicker(level: Int, onChange: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PracticeAITasks.levels.forEachIndexed { index, title ->
            SegmentedButton(level == index, { onChange(index) }, SegmentedButtonDefaults.itemShape(index, PracticeAITasks.levels.size)) { Text(title) }
        }
    }
}

/** 场景格子，两列；showCustom 时最后加一个“自己描述一个场景” */
@Composable
internal fun PresetGrid(presets: List<PracticePreset>, showCustom: Boolean, onPick: (PracticePreset) -> Unit, onCustom: () -> Unit, detailed: Boolean = false) {
    val colors = Lx.colors
    val cells: List<PracticePreset?> = presets + if (showCustom) listOf(null) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val shape = RoundedCornerShape(16.dp)
                    if (preset == null) {
                        val ink = colors.practiceInk
                        Row(
                            Modifier.weight(1f).heightIn(min = 56.dp).clip(shape)
                                .drawBehind {
                                    drawRoundRect(
                                        ink.copy(alpha = 0.4f), cornerRadius = CornerRadius(16.dp.toPx()),
                                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                                    )
                                }
                                .clickable(onClick = onCustom).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.EditNote, null, tint = ink, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("自己描述一个场景", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink)
                        }
                    } else {
                        Column(
                            Modifier.weight(1f).clip(shape).background(colors.practiceCard).clickable { onPick(preset) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(preset.icon, null, tint = colors.practiceInk, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(preset.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.practiceInk)
                            }
                            Text(
                                if (detailed) "AI 是${preset.role} · ${preset.detail}" else "AI 是${preset.role}",
                                fontSize = 12.sp, color = colors.ink3, minLines = if (detailed) 2 else 1, maxLines = 2,
                            )
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
