package com.yishulabs.qtranslator.modules

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.Interpreter
import com.yishulabs.qtranslator.core.OfflineTranslator
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.QuickTranslator
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 正在进行的同声传译。它不属于某个页面：离开传译页、切到别的模块，甚至回到手机桌面，都继续收音、转录、翻译和朗读
 * （后台由 InterpretService 这个前台服务保活）。结束时整段字幕存成同声传译模块里的一条记录。
 * 对应苹果版 Modules/InterpretSession.swift。
 */
object InterpretSession {
    /** 正在传译（包括暂停中） */
    var interpreter by mutableStateOf<Interpreter?>(null)
        private set
    /** 传译页正在显示；false 时在别的页面显示小提示条 */
    var presented by mutableStateOf(false)
    /** 正在结束、保存 */
    var finishing by mutableStateOf(false)
        private set
    /** 翻译用的是本机离线模型（显示给用户看） */
    var translatorOnDevice by mutableStateOf(false)
        private set

    /** 接着录的那条记录，以及它已经有的字幕和时长（秒） */
    var continuing: String? = null
        private set
    var previous: List<TranscriptLine> = emptyList()
        private set
    var previousDuration = 0.0
        private set
    /** 这次接着录是从什么时候开始的（分隔线上显示） */
    var continuedAt = 0L
        private set

    /** 这段传译中途收起过或者回到过桌面（统计用） */
    var wentToBackground = false

    /** 首页选的默认方向（true 听中文译成英语）和字幕显示方式（0 对照，1 原文，2 译文），键名和苹果版一致 */
    var defaultFromChinese by mutableStateOf(Prefs.store.getBoolean("interpreter.sourceIsChinese", false))
        private set
    var display by mutableIntStateOf(Prefs.store.getInt("interpreter.display", 0))
        private set

    fun updateDefaultFromChinese(value: Boolean) {
        defaultFromChinese = value
        Prefs.store.edit().putBoolean("interpreter.sourceIsChinese", value).apply()
    }

    fun updateDisplay(value: Int) {
        display = value
        Prefs.store.edit().putInt("interpreter.display", value).apply()
    }

    val isActive: Boolean get() = interpreter != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    /** 识别已经开始过（没有 Context 时推迟到传译页出现再开始） */
    private var started = false

    /** 记下 app 的 Context：开始收音、启动前台服务要用 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** 开始一段新的传译，或者接着某条记录录（continuing）。已经在传译时只是回到传译页 */
    fun begin(continuing: String?) {
        presented = true
        if (interpreter != null) return
        val record = continuing?.let { ModuleStore.interpretation(it) }
        this.continuing = record?.id
        previous = record?.lines ?: emptyList()
        previousDuration = record?.duration ?: 0.0
        continuedAt = System.currentTimeMillis()
        val interpreter = Interpreter { text, chinese, live -> translate(text, chinese, live) }
        this.interpreter = interpreter
        started = false
        // 麦克风只能给一个人用：传译期间不能用语音输入
        VoiceInput.interpreterActive = true
        // 继续录时沿用那条记录的方向（不改首页的默认方向）
        val chinese = record?.sourceIsChinese ?: defaultFromChinese
        wentToBackground = false
        Analytics.track(
            Analytics.Event.INTERPRET_START,
            mapOf(
                "direction" to Analytics.direction(chinese), "continue" to if (record == null) "no" else "yes",
                "speak" to if (interpreter.speakTranslations) "on" else "off",
            ),
        )
        appContext?.let { ensureStarted(it) }
    }

    /** 传译页出现时调用：还没开始收音就开始 */
    fun ensureStarted(context: Context) {
        attach(context)
        val interpreter = interpreter ?: return
        if (started) return
        started = true
        val chinese = continuing?.let { ModuleStore.interpretation(it)?.sourceIsChinese } ?: defaultFromChinese
        listen(context, interpreter, chinese)
    }

    /** 出错后点“重试” */
    fun retry(context: Context) {
        val interpreter = interpreter ?: return
        listen(context, interpreter, interpreter.sourceIsChinese)
    }

    private fun listen(context: Context, interpreter: Interpreter, chinese: Boolean) {
        scope.launch { prepareTranslator(chinese) }
        interpreter.start(context, chinese)
        if (interpreter.isActive) {
            InterpretService.start(context)
            // 后台把另一种语言的识别模型也准备好，中途切换不用等
            interpreter.prepareOtherLanguage()
        }
    }

    /** 换方向：前面的字幕保留，后面按新语言识别 */
    fun switchDirection(toChinese: Boolean) {
        val interpreter = interpreter ?: return
        if (interpreter.sourceIsChinese == toChinese) return
        Analytics.track(Analytics.Event.INTERPRET_SWITCH, mapOf("to" to Analytics.direction(toChinese)))
        interpreter.switchDirection()
        scope.launch { prepareTranslator(toChinese) }
        if (continuing == null) updateDefaultFromChinese(interpreter.sourceIsChinese)
    }

    /** 传译页收起，传译在后台继续 */
    fun minimize() {
        presented = false
        if (isActive) {
            wentToBackground = true
            Analytics.track(Analytics.Event.INTERPRET_MINIMIZE)
        }
    }

    /** 结束并保存。最后几句还在翻译时最多等 3 秒 */
    fun finish(onDone: () -> Unit = {}) {
        val interpreter = interpreter ?: return onDone()
        if (finishing) return
        finishing = true
        interpreter.stop()
        scope.launch {
            withTimeoutOrNull(3000) { while (interpreter.translating) delay(100) }
            val lines = interpreter.segments.map { TranscriptLine(original = it.original, translation = it.translation ?: "") }
            val seconds = interpreter.elapsed / 1000.0
            Analytics.track(
                Analytics.Event.INTERPRET_FINISH,
                mapOf(
                    "minutes" to Analytics.bucket((seconds / 60).toInt(), listOf(1, 10, 30, 60)),
                    "sentences" to Analytics.bucket(lines.size, listOf(0, 10, 50, 200)),
                    "background" to if (wentToBackground) "yes" else "no",
                ),
            )
            ModuleStore.saveInterpretation(lines, seconds, interpreter.sourceIsChinese, into = continuing)
            Speaker.stop()
            interpreter.release()
            VoiceInput.interpreterActive = false
            appContext?.let { InterpretService.stop(it) }
            this@InterpretSession.interpreter = null
            presented = false
            continuing = null
            previous = emptyList()
            previousDuration = 0.0
            started = false
            finishing = false
            onDone()
        }
    }

    /** 提示条和通知里显示的最新一句 */
    fun latestLine(interpreter: Interpreter): String {
        if (interpreter.live.isNotEmpty()) return interpreter.live
        val last = interpreter.segments.lastOrNull()
            ?: return if (interpreter.state == Interpreter.State.Paused) "已暂停" else "正在听…"
        return last.translation ?: last.original
    }

    // 翻译：本机离线模型下载好了就用它（快、不限量），否则用在线翻译。没说完的半句只在本机翻，不走在线

    private suspend fun translate(text: String, fromChinese: Boolean, live: Boolean): String? {
        if (live) {
            if (!QuickTranslator.onDevice) return null
            return runCatching { OfflineTranslator.translate(text, fromChinese) }.getOrNull()
        }
        return QuickTranslator.translate(text, fromChinese)
    }

    /** 检查本机翻译能不能用，能用就先翻一个词，把模型加载好 */
    private suspend fun prepareTranslator(fromChinese: Boolean) {
        QuickTranslator.refresh()
        translatorOnDevice = QuickTranslator.onDevice
        if (translatorOnDevice) runCatching { OfflineTranslator.translate(if (fromChinese) "你好" else "hello", fromChinese) }
    }
}
