package com.yishulabs.qtranslator.ui.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Work
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.PracticeAITasks
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.modules.ModuleStore
import com.yishulabs.qtranslator.modules.PracticeLine
import com.yishulabs.qtranslator.modules.PracticeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 场景练习的数据和逻辑：预设场景、练习的选项、进行中的一次练习。对应苹果版 PracticeView.swift

/** 预设的练习场景 */
data class PracticePreset(val title: String, val role: String, val detail: String, val icon: ImageVector) {
    val scenario: String get() = "$title：$detail"

    companion object {
        val all = listOf(
            PracticePreset("租房", "房东", "暖气坏了，和房东约时间来修", Icons.Rounded.Home),
            PracticePreset("餐厅点餐", "服务员", "点餐、问推荐、结账", Icons.Rounded.Restaurant),
            PracticePreset("酒店入住", "前台", "办理入住，问早餐和退房时间", Icons.Rounded.Hotel),
            PracticePreset("机场值机", "地勤", "值机、托运行李、选座位", Icons.Rounded.Flight),
            PracticePreset("看医生", "医生", "描述症状，听医生的建议", Icons.Rounded.MedicalServices),
            PracticePreset("工作面试", "面试官", "介绍自己的经历，回答面试问题", Icons.Rounded.Work),
            PracticePreset("购物退换", "店员", "问尺码、试穿、退换货", Icons.Rounded.ShoppingCart),
            PracticePreset("课堂讨论", "老师", "课上提问、讨论作业", Icons.Rounded.School),
            PracticePreset("同事闲聊", "同事", "茶水间聊周末和工作", Icons.Rounded.LocalCafe),
        )
    }
}

/** 场景练习的选项，存在普通设置里，键名和苹果版一致 */
object PracticePrefs {
    var level by mutableIntStateOf(Prefs.store.getInt("practice.level", 1))
        private set
    /** 自动朗读 AI 说的话 */
    var speak by mutableStateOf(Prefs.store.getBoolean("practice.speak", true))
        private set
    /** 语音聊天：像语音通话一样，对方说完自动开始听，我说完停一下自动发送 */
    var handsFree by mutableStateOf(Prefs.store.getBoolean("practice.handsFree", false))
        private set
    /** 一键显示所有中文意思 */
    var showChinese by mutableStateOf(Prefs.store.getBoolean("practice.showChinese", false))
        private set

    fun updateLevel(value: Int) { level = value; Prefs.store.edit().putInt("practice.level", value).apply() }
    fun updateSpeak(value: Boolean) { speak = value; Prefs.store.edit().putBoolean("practice.speak", value).apply() }
    fun updateHandsFree(value: Boolean) { handsFree = value; Prefs.store.edit().putBoolean("practice.handsFree", value).apply() }
    fun updateShowChinese(value: Boolean) { showChinese = value; Prefs.store.edit().putBoolean("practice.showChinese", value).apply() }
}

/** 记录的标题，例如“租房 · AI 是房东” */
fun PracticeRecord.title(): String = scenario.substringBefore("：") + " · AI 是" + role.ifEmpty { "对方" }

/** 重新练同一个场景：只留场景、角色和难度 */
fun PracticeRecord.fresh(): PracticeRecord = PracticeRecord(scenario, role, level)

/** 统计用的分档，和苹果版 Analytics.bucket 一致 */
internal fun bucket(value: Int, edges: List<Int>): String {
    edges.forEachIndexed { i, edge -> if (value <= edge) return if (i == 0) "≤$edge" else "${edges[i - 1] + 1}-$edge" }
    return ">${edges.lastOrNull() ?: 0}"
}

/** 进行中的一次练习：发送、AI 回复、重试、结束。每一轮都存进练习记录 */
class PracticeSession(seed: PracticeRecord?, private val scope: CoroutineScope) {
    var record by mutableStateOf(seed?.fresh())
        private set
    var thinking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showSummary by mutableStateOf(false)
    /** 点开看中文意思的那几句 */
    var revealed by mutableStateOf(setOf<String>())
    /** 这次练习存在记录里的那一条 */
    private var savedId: String? = null

    fun toggleReveal(id: String) {
        revealed = if (id in revealed) revealed - id else revealed + id
    }

    fun begin(scenario: String, role: String, level: Int) {
        record = PracticeRecord(scenario, role, level)
        savedId = null
        trackStart()
        respond()
    }

    /** 首页点了场景直接进来：开场 */
    fun openIfNeeded() {
        val current = record ?: return
        if (current.lines.isEmpty() && !thinking) {
            trackStart()
            respond()
        }
    }

    /** 统计：练的是哪个场景（预设场景记名字，自己描述的只记“自定义”） */
    private fun trackStart() {
        val current = record ?: return
        val preset = PracticePreset.all.firstOrNull { it.scenario == current.scenario }
        Analytics.track(
            Analytics.Event.PRACTICE_START,
            mapOf(
                "scene" to (preset?.title ?: "自定义"), "level" to PracticeAITasks.levelTitle(current.level),
                "hands_free" to if (PracticePrefs.handsFree) "yes" else "no",
            ),
        )
    }

    /** input：text 打字，voice 语音，voice_chat 语音聊天 */
    fun send(text: String, by: String) {
        val current = record ?: return
        if (text.isBlank() || thinking) return
        Analytics.track(Analytics.Event.PRACTICE_REPLY, mapOf("input" to by))
        error = null
        record = current.copy(lines = current.lines + PracticeLine(isMine = true, text = text.trim()))
        respond()
    }

    /** 让 AI 接着说：没有内容时开场，否则回应最后一句我说的话 */
    fun respond() {
        val current = record ?: return
        if (thinking || current.lines.lastOrNull()?.isMine == false) return
        thinking = true
        error = null
        scope.launch {
            try {
                val reply = PracticeAITasks.practiceTurn(current.scenario, current.role, current.level, current.lines, AISettings.currentConfig)
                // 换了场景或者已经重来过：丢掉这次结果
                val latest = record
                if (latest == null || latest.scenario != current.scenario || latest.lines.size != current.lines.size) return@launch
                val lines = latest.lines.toMutableList()
                val last = lines.lastIndex
                if (last >= 0 && lines[last].isMine) {
                    lines[last] = lines[last].copy(better = reply.better, reason = if (reply.better == null) null else reply.reason)
                }
                val phrases = latest.phrases.toMutableList()
                reply.phrases.forEach { phrase ->
                    if (phrases.none { it.key.equals(phrase.key, ignoreCase = true) }) phrases += phrase
                }
                lines += PracticeLine(isMine = false, text = reply.reply, chinese = reply.chinese)
                val updated = latest.copy(
                    role = latest.role.ifEmpty { reply.role ?: "对方" }, lines = lines, phrases = phrases,
                )
                record = updated
                savedId = ModuleStore.savePractice(updated, savedId)
                // 先开始朗读再结束“思考”：语音聊天不会在朗读前抢先开始听
                if (PracticePrefs.speak) Speaker.play(Speech.english(reply.reply))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "出错了。"
            } finally {
                thinking = false
            }
        }
    }

    /** 结束：说过话就存好、显示小结（返回 true）；没说过话直接关掉（返回 false） */
    fun finish(): Boolean {
        Speaker.stop()
        val current = record ?: return false
        val mine = current.lines.count { it.isMine }
        if (mine == 0) return false
        savedId = ModuleStore.savePractice(current, savedId)
        Analytics.track(
            Analytics.Event.PRACTICE_FINISH,
            mapOf(
                "lines" to bucket(mine, listOf(2, 5, 10, 20)),
                "corrections" to bucket(current.lines.count { it.better != null }, listOf(0, 2, 5)),
            ),
        )
        showSummary = true
        return true
    }

    /** 同一个场景重新来一次：这次的记录已经存好了，新开一条 */
    fun again() {
        showSummary = false
        val old = record ?: return
        record = old.fresh()
        savedId = null
        revealed = emptySet()
        error = null
        trackStart()
        respond()
    }
}
