package com.yishulabs.qtranslator.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * 语音输入：用系统的语音识别把说的话实时变成文字。能在本机识别时优先本机识别。
 *
 * 和苹果版的 VoiceInput 是同一套接口：start / stop / cancel / switchLanguage，界面读 text、levels、state。
 * 安卓的识别器说完一句、停顿一下就会自己结束；这里在结束后自动接着听，直到调用 stop，
 * 前面识别出的文字保留在 finalText 里。
 */
object VoiceInput {
    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Failed(val message: String) : State
    }

    /** 识别的语言：英语或中文 */
    enum class Language(val tag: String, val title: String) {
        ENGLISH("en-US", "英语"),
        CHINESE("zh-CN", "中文");

        val toggled: Language get() = if (this == ENGLISH) CHINESE else ENGLISH
    }

    /** 说完一句话后的结果：识别出的文字、原声文件名（filesDir/audio 下，没录到是 null）和时长（秒） */
    data class Result(val text: String, val audio: String?, val duration: Double)

    var state by mutableStateOf<State>(State.Idle)
        private set
    /** 已经说完的部分和还在变化的部分，界面上后者用浅色显示 */
    var finalText by mutableStateOf("")
        private set
    var volatileText by mutableStateOf("")
        private set
    var language by mutableStateOf(Language.ENGLISH)
        private set
    /** 最近一段的音量（0…1），用来画声波 */
    var levels by mutableStateOf(List(24) { 0f })
        private set
    var startedAt by mutableStateOf<Long?>(null)
        private set

    val text: String get() = (finalText + volatileText).trim()
    val isListening: Boolean get() = state == State.Listening

    /** 同声传译正在收音时不能用语音输入（麦克风只能给一个人用）；传译开始和结束时设置 */
    var interpreterActive = false

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    /** 这一次 start 的编号：旧识别器迟到的回调直接丢掉 */
    private var generation = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("voice.language", null)
        language = Language.entries.firstOrNull { it.tag == saved } ?: Language.ENGLISH
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 这台设备有没有可用的语音识别 */
    fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    // 开始和结束

    /** 开始听。preferred 为 null 时用上次用的语言。调用前要先拿到麦克风权限（见 rememberMicPermission） */
    fun start(preferred: Language? = null) {
        if (interpreterActive) {
            state = State.Failed("同声传译正在收音，先结束传译再用语音输入")
            return
        }
        if (state == State.Listening) return
        preferred?.let { chooseLanguage(it) }
        if (!hasPermission(appContext)) {
            state = State.Failed("需要允许使用麦克风，可以在系统设置里打开。")
            return
        }
        if (!isAvailable(appContext)) {
            state = State.Failed("这台手机没有可用的语音识别服务。")
            return
        }
        finalText = ""
        volatileText = ""
        levels = List(levels.size) { 0f }
        generation++
        startedAt = System.currentTimeMillis()
        state = State.Listening
        listen(generation)
    }

    /** 说完了：返回识别出的文字、原声文件名和时长 */
    fun stop(): Result {
        val result = Result(text, null, startedAt?.let { (System.currentTimeMillis() - it) / 1000.0 } ?: 0.0)
        end()
        return result
    }

    /** 取消：不要文字，也不要录音 */
    fun cancel() {
        end()
        finalText = ""
        volatileText = ""
    }

    /** 说到一半切换中英：前面识别出的文字保留，后面按新语言识别 */
    fun switchLanguage() {
        chooseLanguage(language.toggled)
        if (state != State.Listening) return
        commitVolatile()
        generation++
        destroyRecognizer()
        listen(generation)
    }

    fun clearError() {
        if (state is State.Failed) state = State.Idle
    }

    private fun chooseLanguage(value: Language) {
        language = value
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("voice.language", value.tag).apply()
    }

    private fun end() {
        generation++
        destroyRecognizer()
        state = State.Idle
        startedAt = null
        levels = List(levels.size) { 0f }
    }

    private fun commitVolatile() {
        if (volatileText.isNotBlank()) finalText = join(finalText, volatileText)
        volatileText = ""
    }

    /** 中文直接接上，英文中间加空格 */
    private fun join(a: String, b: String): String {
        if (a.isBlank()) return b.trim()
        val glue = if (language == Language.CHINESE) "" else " "
        return a.trimEnd() + glue + b.trim()
    }

    // 识别

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer {
        // 安卓 13 起能确定本机识别器可用时优先用它：不联网、录音不离开手机
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        }
        return SpeechRecognizer.createSpeechRecognizer(appContext)
    }

    private fun listen(gen: Int) {
        val recognizer = createRecognizer()
        this.recognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                if (gen != generation) return
                // rmsdB 大约在 -2…10 之间
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                levels = levels.drop(1) + level
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (gen != generation) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { volatileText = it }
            }

            override fun onResults(results: Bundle?) {
                if (gen != generation) return
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!best.isNullOrBlank()) volatileText = best
                commitVolatile()
                restart(gen)
            }

            override fun onError(error: Int) {
                if (gen != generation) return
                when (error) {
                    // 没听到声音、没听清：接着听
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        commitVolatile()
                        restart(gen)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("需要允许使用麦克风，可以在系统设置里打开。")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        fail("语音识别需要联网，或者先在系统设置里下载离线语音包。")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(gen, delayMs = 300)
                    else -> fail("语音识别出错了（$error），再试一次。")
                }
            }
        })
        recognizer.startListening(intent())
    }

    private fun restart(gen: Int, delayMs: Long = 0) {
        main.postDelayed({
            if (gen != generation || state != State.Listening) return@postDelayed
            destroyRecognizer()
            listen(gen)
        }, delayMs)
    }

    private fun fail(message: String) {
        end()
        state = State.Failed(message)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 停顿多久算说完一句：尽量给长一点，少断句
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
    }
}
