package com.yishulabs.qtranslator.ui

import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.border

import com.yishulabs.qtranslator.modules.InterpretSession

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.BuildConfig
import com.yishulabs.qtranslator.ai.AIClient
import com.yishulabs.qtranslator.ai.AIProvider
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.Pricing
import com.yishulabs.qtranslator.ai.UsageStore
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.analytics.AnalyticsPage
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.VoiceInput
import kotlinx.coroutines.launch
import java.util.Calendar

/** 设置里的子页面 */
private enum class SettingsPage { MAIN, AI, REPORT, VOICES, OFFLINE }

/** 设置：外观、离线模型、AI 服务商和 key、累计用量和报表、各功能的选项、朗读、翻译来源、隐私。往下拉关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit, startAtAI: Boolean = false) {
    var page by remember { mutableStateOf(if (startAtAI) SettingsPage.AI else SettingsPage.MAIN) }
    AnalyticsPage("设置")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Lx.colors.background,
    ) {
        BackHandler(enabled = page != SettingsPage.MAIN) { page = if (page == SettingsPage.REPORT) SettingsPage.AI else SettingsPage.MAIN }
        Column(Modifier.fillMaxHeight(0.92f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            val back = { page = SettingsPage.MAIN }
            when (page) {
                SettingsPage.MAIN -> SettingsMain(onPage = { page = it })
                SettingsPage.AI -> AISettingsPage(onBack = back, onPage = { page = it })
                SettingsPage.REPORT -> UsageReport(onBack = { page = SettingsPage.AI })
                SettingsPage.VOICES -> SpeechVoicesPage(onBack = back)
                SettingsPage.OFFLINE -> OfflineModelsPage(onBack = back)
            }
        }
    }
}

@Composable
private fun SettingsMain(onPage: (SettingsPage) -> Unit) {
    val colors = Lx.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.ink)
    Spacer(Modifier.height(16.dp))

    Group("外观") {
        ChoiceRow("主题", listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色"), Prefs.appearance) { Prefs.updateAppearance(it) }
    }

    Group(null) {
        SettingRow("离线翻译和语音识别模型", onClick = { onPage(SettingsPage.OFFLINE) }) {
            Icon(Icons.Rounded.Download, null, tint = colors.accent)
            Icon(Icons.Rounded.ChevronRight, null, tint = colors.ink3)
        }
    }
    Footnote("下载后不用联网、不限量，翻译和同声传译都会快很多。")

    Group(null) {
        SettingRow("AI 增强", onClick = { onPage(SettingsPage.AI) }) {
            Text(
                if (AISettings.isConfigured) AISettings.provider.title else "未配置",
                color = if (AISettings.isConfigured) colors.ink3 else colors.ai, fontSize = 15.sp,
            )
            Icon(Icons.Rounded.ChevronRight, null, tint = colors.ink3)
        }
    }
    Footnote("优化译文、写回复、场景练习、AI 音色要用到。可选，不填也能用词典、翻译、朗读和图片翻译。")

    Group("语音输入") {
        SettingRow("说完自动翻译") { Switch(Prefs.voiceAutoSend, { Prefs.updateVoiceAutoSend(it) }) }
        SettingsDivider()
        ChoiceRow(
            "“自动”方向时先听",
            listOf(VoiceInput.Language.ENGLISH to "英语", VoiceInput.Language.CHINESE to "中文"),
            VoiceInput.language,
        ) {
            // VoiceInput 只提供切换：不在听的时候切换一下就是改默认语言（会保存）
            if (it != VoiceInput.language && !VoiceInput.isListening) VoiceInput.switchLanguage()
        }
    }
    Footnote("关闭“说完自动翻译”时，说的话先放进输入框，可以改完再翻译。翻译方向选了中→英或英→中时，按方向识别；正在听的时候也可以点一下切换。")

    Group("同声传译") {
        ChoiceRow("默认方向", listOf(false to "英 → 中", true to "中 → 英"), InterpretSession.defaultFromChinese) {
            InterpretSession.updateDefaultFromChinese(it)
        }
        SettingsDivider()
        SettingRow("默认朗读译文（建议戴耳机）") { Switch(Prefs.interpreterSpeak, { Prefs.updateInterpreterSpeak(it) }) }
    }

    Group("面对面对话") {
        SettingRow("翻译后朗读出来") { Switch(Prefs.dialogSpeak, { Prefs.updateDialogSpeak(it) }) }
    }

    Group("朗读") {
        SpeechSpeedRow()
        SettingsDivider()
        ChoiceRow("默认英文口音", listOf(1 to "英式", 2 to "美式"), Prefs.accent) { Prefs.updateAccent(it) }
        SettingsDivider()
        SettingRow("查词后自动朗读") { Switch(Prefs.autoSpeak, { Prefs.updateAutoSpeak(it) }) }
        SettingsDivider()
        SettingRow("音色", onClick = { onPage(SettingsPage.VOICES) }) { Icon(Icons.Rounded.ChevronRight, null, tint = colors.ink3) }
    }

    Group("翻译来源") {
        SettingRow("单词", value = "有道词典（在线）")
        SettingsDivider()
        SettingRow("句子和段落", value = "本机离线翻译，其次 MyMemory")
        SettingsDivider()
        SettingRow("图片文字", value = "本机识别，不上传")
        SettingsDivider()
        SettingRow("语音", value = "系统语音识别，优先本机")
    }
    Footnote("先用离线和免费的来源，AI 只在你填了 Key 之后作为补充。")

    var showPrivacy by remember { mutableStateOf(false) }
    if (showPrivacy) com.yishulabs.qtranslator.analytics.PrivacyPolicyDialog { showPrivacy = false }
    Group("关于") {
        SettingRow("版本", value = BuildConfig.VERSION_NAME)
        SettingsDivider()
        SettingRow("隐私协议", onClick = { showPrivacy = true }) { Icon(Icons.Rounded.ChevronRight, null, tint = colors.ink3) }
        SettingsDivider()
        SettingRow("源代码（MIT 许可）", onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aquila0625/Q-Translator")))
        }) { Icon(Icons.Rounded.ChevronRight, null, tint = colors.ink3) }
    }
}

/** 一行标题加分段选择，例如主题、口音 */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = Lx.colors.ink)
        // 自己画的分段选择：系统的分段按钮内边距固定，“跟随系统”四个字会被截断
        val shape = RoundedCornerShape(50)
        Row(Modifier.height(34.dp).clip(shape).border(1.dp, Lx.colors.line, shape)) {
            options.forEachIndexed { index, (value, label) ->
                val on = selected == value
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Lx.colors.line))
                Box(
                    Modifier.fillMaxHeight().background(if (on) Lx.colors.accentSoft else Color.Transparent)
                        .clickable { onSelect(value) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, maxLines = 1, softWrap = false, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Lx.colors.accent else Lx.colors.ink,
                    )
                }
            }
        }
    }
}

/** 子页面的标题栏：返回设置 */
@Composable
fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回设置", tint = Lx.colors.accent) }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Lx.colors.ink)
    }
    Spacer(Modifier.height(12.dp))
}

/** AI 增强：服务商、API Key、模型、测试连接和用量。从设置进入，也可以在别处提示“需要配置 AI”时直接打开 */
@Composable
private fun AISettingsPage(onBack: () -> Unit, onPage: (SettingsPage) -> Unit) {
    val colors = Lx.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    SettingsPageHeader("AI 增强", onBack)
    Group(null) {
        Box {
            SettingRow("服务商", value = AISettings.provider.title, onClick = { providerMenu = true })
            DropdownMenu(providerMenu, { providerMenu = false }) {
                AIProvider.entries.forEach { p ->
                    DropdownMenuItem(text = { Text(p.title) }, onClick = {
                        providerMenu = false
                        if (p != AISettings.provider) Analytics.track(Analytics.Event.SETTINGS_AI_PROVIDER, mapOf("provider" to p.key))
                        AISettings.updateProvider(p)
                        testResult = null
                    })
                }
            }
        }
        SettingsDivider()
        OutlinedTextField(
            AISettings.apiKey, { AISettings.updateApiKey(it) }, label = { Text("API Key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        AISettings.provider.signupUrl?.let { url ->
            Text(
                "去 ${AISettings.provider.title} 注册并获取 API Key", color = colors.accent, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(16.dp),
            )
            SettingsDivider()
        }
        if (AISettings.provider == AIProvider.CUSTOM) {
            OutlinedTextField(
                AISettings.baseUrl, { AISettings.updateBaseUrl(it) }, label = { Text("接口地址，例如 https://api.openai.com/v1") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        if (AISettings.provider.suggestedModels.isNotEmpty()) {
            Box {
                SettingRow("模型", value = AISettings.model.ifEmpty { "未选择" }, onClick = { modelMenu = true })
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    AISettings.provider.modelGroups.forEach { (group, models) ->
                        Text(group, fontSize = 12.sp, color = colors.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        models.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = {
                                modelMenu = false
                                AISettings.updateModel(m)
                            })
                        }
                    }
                }
            }
            SettingsDivider()
        }
        OutlinedTextField(
            AISettings.model, { AISettings.updateModel(it) }, label = { Text("或手动填写模型名称") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        SettingRow("AI 优化：新会话默认开启") { Switch(AISettings.autoCalibrate, { AISettings.updateAutoCalibrate(it) }) }
        SettingsDivider()
        SettingRow("在译文下显示每次消耗的 token") { Switch(Prefs.showAIUsage, { Prefs.updateShowAIUsage(it) }) }
        SettingsDivider()
        SettingRow("测试连接", value = testResult, enabled = !testing && AISettings.isConfigured, onClick = {
            testing = true
            testResult = null
            scope.launch {
                testResult = try {
                    AIClient.complete("This is a connectivity check from an app's settings screen. Reply with the single word OK.", "ping", AISettings.currentConfig)
                    "连接正常"
                } catch (e: Exception) {
                    e.message
                }
                testing = false
            }
        }) { if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        SettingsDivider()
        val summary = UsageStore.summarize(UsageStore.records(AISettings.provider))
        SettingRow(
            "${AISettings.provider.title} 累计用量",
            value = "${summary.total} tokens",
            subtitle = when {
                summary.total == 0 -> "还没有用过"
                summary.cost > 0 -> "约 ${Pricing.format(summary.cost)}（按标价估算）"
                else -> "无价格数据"
            },
        )
        SettingsDivider()
        SettingRow("用量报表", onClick = { onPage(SettingsPage.REPORT) }) { Icon(Icons.Rounded.ChevronRight, null, tint = colors.ink3) }
    }
    Footnote(
        "Q-Translator 不提供 AI 额度，也不经过任何中间服务器：你自己在服务商那里注册，把 API Key 填在这里，费用由服务商向你收取。" +
            "Key 只加密保存在本机。注意 ChatGPT 的会员订阅不包含 API 额度，API Key 要在 OpenAI 开发者平台单独申请。" +
            "不填也能使用词典、翻译、朗读和图片翻译。AI 用于优化句子翻译和帮你写回复。单词和短语只查词典，不用 AI。" +
            "token 用量默认不显示在译文下面，可以在用量报表里查看。"
    )

}

/** 用量报表：按天统计，可以看最近一周或本月，可以按服务商筛选 */
@Composable
private fun UsageReport(onBack: () -> Unit) {
    val colors = Lx.colors
    var month by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<AIProvider?>(AISettings.provider) }
    var providerMenu by remember { mutableStateOf(false) }

    val days: List<Long> = remember(month) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (month) {
            val count = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            List(count) { (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, it) }.timeInMillis }
        } else {
            List(7) { (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, it - 6) }.timeInMillis }
        }
    }
    val dayMs = 24 * 3600 * 1000L
    val records = UsageStore.records(provider).filter { it.date >= days.first() && it.date < days.last() + dayMs }
    val totals = days.map { start -> records.filter { it.date >= start && it.date < start + dayMs }.sumOf { it.total } }
    val summary = UsageStore.summarize(records)

    SettingsPageHeader("用量报表", onBack)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(!month, { month = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("周") }
        SegmentedButton(month, { month = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("月") }
    }
    Spacer(Modifier.height(12.dp))
    Group(if (month) "本月" else "最近 7 天") {
        Box {
            SettingRow("服务商", value = provider?.title ?: "全部", onClick = { providerMenu = true }) {
                Icon(Icons.Rounded.ExpandMore, null, tint = colors.ink3)
            }
            DropdownMenu(providerMenu, { providerMenu = false }) {
                DropdownMenuItem(text = { Text("全部") }, onClick = { provider = null; providerMenu = false })
                AIProvider.entries.forEach { p -> DropdownMenuItem(text = { Text(p.title) }, onClick = { provider = p; providerMenu = false }) }
            }
        }
        SettingsDivider()
        SettingRow("token 合计", value = summary.total.toString())
        SettingsDivider()
        SettingRow("输入 / 输出", value = "${summary.input} / ${summary.output}")
        SettingsDivider()
        SettingRow("估算费用", value = when {
            summary.total == 0 -> "—"
            summary.cost == 0.0 && summary.hasUnpriced -> "无价格数据"
            else -> "约 " + Pricing.format(summary.cost) + if (summary.hasUnpriced) "（部分无价格）" else ""
        })
        // 柱状图
        val top = (totals.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(
            Modifier.fillMaxWidth().height(170.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (month) 2.dp else 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            totals.forEach { value ->
                Box(
                    Modifier.weight(1f).fillMaxHeight(maxOf(0.015f, value.toFloat() / top))
                        .background(colors.accent.copy(alpha = if (value > 0) 1f else 0.25f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (month) 2.dp else 8.dp)) {
            days.forEach { start ->
                val cal = Calendar.getInstance().apply { timeInMillis = start }
                val label = if (month) {
                    val d = cal.get(Calendar.DAY_OF_MONTH)
                    if (d == 1 || d % 5 == 0) d.toString() else ""
                } else {
                    "日一二三四五六"[cal.get(Calendar.DAY_OF_WEEK) - 1].toString()
                }
                Text(label, fontSize = 11.sp, color = colors.ink3, modifier = Modifier.weight(1f), maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    val byModel = records.groupBy { it.model }.map { (model, list) -> model to UsageStore.summarize(list) }.sortedByDescending { it.second.total }
    if (byModel.isNotEmpty()) {
        Group("按模型") {
            byModel.forEachIndexed { index, (model, s) ->
                if (index > 0) SettingsDivider()
                SettingRow(model, value = "${s.total} tokens", subtitle = if (s.cost > 0) "约 " + Pricing.format(s.cost) else "无价格数据")
            }
        }
    }
    Footnote("费用按服务商公开的标价估算，实际以服务商账单为准。DeepSeek 和自定义接口没有价格数据，只统计 token。")
}

@Composable
fun Group(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        if (title != null) SectionHeader(title, Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Lx.colors.surface)) { content() }
    }
}

@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Lx.colors
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled || onClick == null) colors.ink else colors.ink3, fontSize = 16.sp)
            subtitle?.let { Text(it, color = colors.ink3, fontSize = 12.sp) }
        }
        value?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = if (onClick != null) colors.accent else colors.ink3, fontSize = 15.sp, maxLines = 2)
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

@Composable
fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Lx.colors.line)

@Composable
fun Footnote(text: String) {
    Text(text, fontSize = 13.sp, color = Lx.colors.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
