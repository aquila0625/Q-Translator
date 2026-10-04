package com.yishulabs.qtranslator.core

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 同声传译：持续收音，用系统的语音识别（能在本机识别时优先本机）实时转写，一句话说完就翻译，滚动显示双语字幕。
 * 对应苹果版 Core/Interpreter.swift。
 *
 * 安卓的识别器说完一段、停顿一下就会自己结束：结束后马上接着听。安卓 13 起优先用“分段会话”，
 * 一次会话里连续给出多段结果，少重开几次。
 *
 * translate：(文字, 原文是否中文, 是否还没说完的半句) -> 译文
 */
class Interpreter(private val translate: suspend (String, Boolean, Boolean) -> String?) {
    data class Segment(val id: String = UUID.randomUUID().toString(), val original: String, val translation: String? = null)

    sealed interface State {
        data object Idle : State
        data class Preparing(val message: String) : State
        data object Running : State
        data object Paused : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set
    var segments by mutableStateOf(listOf<Segment>())
        private set
    /** 还在说、没说完的那一句，以及它边说边翻的译文 */
    var live by mutableStateOf("")
        private set
    var liveTranslation by mutableStateOf("")
        private set
    var levels by mutableStateOf(List(30) { 0f })
        private set
    var startedAt by mutableStateOf<Long?>(null)
        private set
    /** 原文语言：true 是中文（中 → 英），false 是英语（英 → 中） */
    var sourceIsChinese by mutableStateOf(false)
        private set
    /** 用耳机朗读译文 */
    var speakTranslations by mutableStateOf(Prefs.interpreterSpeak)
    /** 正在用的识别方式，显示给用户看 */
    var engineName by mutableStateOf("")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private var recognizer: SpeechRecognizer? = null
    /** 每换一次识别器加一；旧识别器晚到的回调按这个丢掉 */
    private var generation = 0
    /** 这一段识别结果里已经切出去的字（只数文字和数字，不数标点和空格）。
     *  识别会回头修改前面的内容（比如补标点），按字符位置记会错位、丢字 */
    private var emittedLetters = 0
    /** 分段会话里上一段的完整结果：有的识别器后面几段会把前面的也带上，要去掉 */
    private var segmentPrefix = ""
    /** 暂停时累计的时长（毫秒） */
    private var elapsedBeforePause = 0L
    private var liveTranslating = false
    private var liveDirty = false
    /** 每收进一句加一：之前那半句晚到的译文不再显示 */
    private var liveEpoch = 0
    /** 还在翻译的句子数；结束时等它们翻完再保存 */
    private var pendingTranslations by mutableStateOf(0)

    /** 本机识别器不支持这种语言（模型没下载）时，这次改用系统识别 */
    private var forceSystem = false
    /** 安卓 13 起的分段会话；识别器不支持时关掉 */
    private var segmented = Build.VERSION.SDK_INT >= 33
    /** 这次识别有没有听到内容 */
    private var heard = false
    /** 连续出错的次数，用来逐渐拉长重开的间隔 */
    private var errorCount = 0
    private var networkErrors = 0

    /** 最近一次收到声音的时间；识别器卡住、超过 4 秒没动静就重开 */
    private var lastAudioAt = 0L
    private var levelsSeen = false
    private var watchdog: Job? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var modeListener: Any? = null

    val elapsed: Long get() = elapsedBeforePause + (startedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L)

    val isActive: Boolean get() = state == State.Running || state == State.Paused

    /** 还有句子没翻完 */
    val translating: Boolean get() = pendingTranslations > 0

    // 开始、暂停、结束

    fun start(context: Context, sourceIsChinese: Boolean) {
        this.context = context.applicationContext
        this.sourceIsChinese = sourceIsChinese
        if (!VoiceInput.hasPermission(context)) {
            state = State.Failed("需要允许使用麦克风和语音识别，可以在系统设置里打开。")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDeviceAvailable()) {
            state = State.Failed("没法开始同传：这台手机没有可用的语音识别服务。")
            return
        }
        forceSystem = false
        errorCount = 0
        networkErrors = 0
        try {
            listen()
        } catch (e: Exception) {
            destroyRecognizer()
            state = State.Failed("没法开始同传：${e.localizedMessage ?: e.javaClass.simpleName}")
            return
        }
        startedAt = SystemClock.elapsedRealtime()
        state = State.Running
        observeAudioChanges()
        startWatchdog()
    }

    fun pause() {
        if (state != State.Running) return
        // 安卓的识别器停下就丢掉没说完的半句：先收进字幕
        destroyRecognizer()
        commitLive()
        elapsedBeforePause = elapsed
        startedAt = null
        levels = List(levels.size) { 0f }
        state = State.Paused
    }

    fun resume() {
        if (state != State.Paused) return
        startedAt = SystemClock.elapsedRealtime()
        state = State.Running
        listen()
    }

    /** 结束：把还没说完的那一句也收进来 */
    fun stop() {
        if (state == State.Idle) return
        destroyRecognizer()
        stopAudio()
        commitLive()
        elapsedBeforePause = elapsed
        startedAt = null
        state = State.Idle
    }

    /** 不再用了：停掉还在翻译的任务 */
    fun release() {
        stop()
        scope.cancel()
    }

    /**
     * 中途换方向：前面的字幕保留，后面按新语言识别。
     * 只换识别器：正在说的半句直接收进字幕，不等旧的识别器收尾，所以很快
     */
    fun switchDirection() {
        val wasRunning = state == State.Running
        if (!wasRunning && state != State.Paused) return
        destroyRecognizer()
        commitLive()
        sourceIsChinese = !sourceIsChinese
        forceSystem = false
        errorCount = 0
        if (wasRunning) listen()
    }

    /**
     * 提前把另一种语言的本机识别模型准备好（需要时让系统在后台下载），中途切换语言时不用再等。
     * 只在安卓 13 起、有本机识别器时有用
     */
    fun prepareOtherLanguage() {
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable()) return
        val other = if (sourceIsChinese) "en-US" else "zh-CN"
        val checker = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return
        val intent = intent(other, segmented = false)
        runCatching {
            checker.checkRecognitionSupport(intent, { main.post(it) }, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages.any { matches(it, other) } ||
                        support.pendingOnDeviceLanguages.any { matches(it, other) }
                    if (!installed && support.supportedOnDeviceLanguages.any { matches(it, other) }) {
                        runCatching { checker.triggerModelDownload(intent) }
                    }
                    runCatching { checker.destroy() }
                }

                override fun onError(error: Int) {
                    runCatching { checker.destroy() }
                }
            })
        }.onFailure { runCatching { checker.destroy() } }
    }

    // 识别

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** 语言标签对不对得上：识别器给的可能是 en-US、cmn-Hans-CN 这样的写法 */
    private fun matches(tag: String, wanted: String): Boolean {
        val t = tag.lowercase()
        return if (wanted.startsWith("zh")) t.startsWith("zh") || t.startsWith("cmn") else t.startsWith("en")
    }

    private fun destroyRecognizer() {
        generation++
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /** 换一个新的识别器开始听 */
    private fun listen() {
        destroyRecognizer()
        val gen = generation
        val onDevice = !forceSystem && onDeviceAvailable()
        val useSegments = segmented && onDevice
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        this.recognizer = recognizer
        engineName = if (onDevice) "系统本机识别" else "系统识别"
        emittedLetters = 0
        segmentPrefix = ""
        heard = false
        lastAudioAt = SystemClock.elapsedRealtime()
        val language = if (sourceIsChinese) "zh-CN" else "en-US"
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = alive(gen)
            override fun onBeginningOfSpeech() = alive(gen)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                if (gen != generation) return
                alive(gen)
                levelsSeen = true
                // rmsdB 大约在 -2…10 之间
                levels = levels.drop(1) + ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (gen != generation) return
                val text = best(partialResults) ?: return
                alive(gen)
                heard = true
                handle(stripPrefix(text), isFinal = false)
            }

            // 分段会话：每说完一段给一次结果，会话接着听
            override fun onSegmentResults(segmentResults: Bundle) {
                if (gen != generation) return
                val text = best(segmentResults)
                heard = true
                errorCount = 0
                if (text != null) {
                    handle(stripPrefix(text), isFinal = true)
                    segmentPrefix = text
                } else {
                    commitLive()
                }
            }

            override fun onEndOfSegmentedSession() {
                if (gen != generation) return
                commitLive()
                restart(gen)
            }

            override fun onResults(results: Bundle?) {
                if (gen != generation) return
                val text = best(results)
                errorCount = 0
                networkErrors = 0
                if (text != null) handle(stripPrefix(text), isFinal = true) else commitLive()
                restart(gen)
            }

            override fun onError(error: Int) {
                if (gen != generation) return
                commitLive()
                when (error) {
                    // 没听到声音、没听清：接着听
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(gen, 100)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("需要允许使用麦克风和语音识别，可以在系统设置里打开。")
                    // 本机识别器不支持这种语言或者模型没下载：让系统在后台下载，这次先用系统识别
                    ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                        if (onDevice) {
                            if (Build.VERSION.SDK_INT >= 33) runCatching { recognizer.triggerModelDownload(intent(language, false)) }
                            forceSystem = true
                            restart(gen, 100)
                        } else {
                            fail("这台设备暂时不支持这种语言的实时识别")
                        }
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> {
                        networkErrors++
                        if (!onDevice && networkErrors >= 3) {
                            fail("语音识别需要联网，或者先在系统设置里下载离线语音包。")
                        } else {
                            restart(gen, 1000)
                        }
                    }
                    else -> {
                        // 分段会话一开始就出错：识别器多半不支持，以后不用了
                        if (useSegments && !heard) segmented = false
                        // 被别的 app 占用麦克风、来电等：隔一会儿再试，越试间隔越长
                        errorCount++
                        restart(gen, (300L * errorCount).coerceAtMost(3000L))
                    }
                }
            }
        })
        recognizer.startListening(intent(language, useSegments))
    }

    private fun alive(gen: Int) {
        if (gen == generation) lastAudioAt = SystemClock.elapsedRealtime()
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun stripPrefix(text: String): String =
        if (segmentPrefix.isNotEmpty() && text.startsWith(segmentPrefix)) text.substring(segmentPrefix.length) else text

    private fun restart(gen: Int, delayMs: Long = 0) {
        main.postDelayed({
            if (gen != generation || state != State.Running) return@postDelayed
            runCatching { listen() }.onFailure { fail("没法开始同传：${it.localizedMessage ?: it.javaClass.simpleName}") }
        }, delayMs)
    }

    private fun fail(message: String) {
        destroyRecognizer()
        stopAudio()
        commitLive()
        elapsedBeforePause = elapsed
        startedAt = null
        state = State.Failed(message)
    }

    private fun intent(language: String, segmented: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (Build.VERSION.SDK_INT >= 33) {
            // 让识别结果带上标点，才能按句子切开
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
        if (segmented && Build.VERSION.SDK_INT >= 33) {
            // 分段会话：一次会话听 10 分钟，中间每段话单独给结果
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10 * 60 * 1000L)
        } else {
            // 停顿多久算说完一段：给长一点，少重开
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
    }

    // 断句

    /** 一段识别结果：已经说完的句子马上切出去翻译，剩下的当作“还在说” */
    private fun handle(text: String, isFinal: Boolean) {
        val emitted = indexAfterLetters(emittedLetters, text)
        var cut = emitted
        // 中文识别常常整段只在最后加句号、中间用逗号，所以一个分句够长时在逗号处也切开，译文才跟得上
        val clauseLimit = if (sourceIsChinese) 14 else 60
        for (i in emitted until text.length) {
            val ch = text[i]
            if (ch !in "。！？!?.，,；;") continue
            // 标点后面还有字，说明这一句已经说完；英文的小数点（2.5）不算
            val next = text.getOrNull(i + 1)
            val prev = text.getOrNull(i - 1)
            if (ch == '.' && prev?.isDigit() == true && next?.isDigit() == true) continue
            if (next == null && !isFinal) continue
            if (ch in "，,；;") {
                if (i + 1 - cut >= clauseLimit) cut = i + 1
            } else {
                cut = i + 1
            }
        }
        if (cut > emitted) {
            val done = text.substring(emitted, cut)
            sentences(done).forEach(::emit)
            emittedLetters += done.count(::isLetter)
        }
        val rest = text.substring(maxOf(cut, emitted)).trim { it.isWhitespace() || isPunctuation(it) }
        if (isFinal) {
            if (rest.isNotEmpty()) emit(rest)
            emittedLetters = 0
            updateLive("")
        } else {
            updateLive(rest)
        }
    }

    private fun isLetter(ch: Char) = ch.isLetterOrDigit()

    private fun isPunctuation(ch: Char) = when (Character.getType(ch).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /** 跳过前 n 个文字后的位置（连同紧跟的标点和空格） */
    private fun indexAfterLetters(n: Int, text: String): Int {
        if (n <= 0) return 0
        var seen = 0
        var i = 0
        while (i < text.length && seen < n) {
            if (isLetter(text[i])) seen++
            i++
        }
        while (i < text.length && !isLetter(text[i])) i++
        return i
    }

    private fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        val limit = if (sourceIsChinese) 14 else 60
        text.forEachIndexed { i, ch ->
            current.append(ch)
            val decimal = ch == '.' && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true
            if (ch in "。！？!?" || (ch == '.' && !decimal && current.length > 1) || (ch in "，,；;" && current.length >= limit)) {
                if (current.isNotBlank()) result += current.toString().trim()
                current.clear()
            }
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    /** 正在说的半句收进字幕（暂停、切换语言、识别器重开前） */
    private fun commitLive() {
        if (live.isNotBlank()) emit(live)
        updateLive("")
        emittedLetters = 0
    }

    private fun updateLive(value: String) {
        if (value == live) return
        live = value
        translateLive()
    }

    /** 边说边翻：还没说完的那句隔一会儿翻一次，同一时间只翻一次，翻完再看有没有新内容 */
    private fun translateLive() {
        val text = live.trim()
        if (text.length <= 2) {
            liveTranslation = ""
            return
        }
        if (liveTranslating) {
            liveDirty = true
            return
        }
        liveTranslating = true
        val chinese = sourceIsChinese
        val epoch = liveEpoch
        scope.launch {
            val result = translate(text, chinese, true)
            liveTranslating = false
            if (epoch == liveEpoch && live.isNotBlank() && result != null) liveTranslation = result
            if (liveDirty) {
                liveDirty = false
                delay(150)
                translateLive()
            }
        }
    }

    private fun emit(sentence: String) {
        val text = sentence.trim()
        if (text.length <= 1) return
        // 说完的这句先用边说边翻的译文顶上，正式译文出来再替换
        val segment = Segment(original = text, translation = liveTranslation.ifEmpty { null })
        liveTranslation = ""
        liveEpoch++
        segments = segments + segment
        val chinese = sourceIsChinese
        pendingTranslations++
        scope.launch {
            val result = translate(text, chinese, false)
            val translation = result ?: "（翻译失败）"
            segments = segments.map { if (it.id == segment.id) it.copy(translation = translation) else it }
            pendingTranslations--
            if (speakTranslations && result != null) Speaker.play(Speech.text(translation, isChinese = !chinese))
        }
    }

    // 声音设备

    /** 插拔耳机、蓝牙连上断开，或者来电、通话结束后，换一个识别器重新听 */
    private fun observeAudioChanges() {
        removeAudioObservers()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var initial = true
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                // 注册时会先报一遍现有的设备，不算变化
                if (initial) {
                    initial = false
                    return
                }
                restartRecognition()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = restartRecognition()
        }
        audio.registerAudioDeviceCallback(callback, main)
        main.post { initial = false }
        deviceCallback = callback
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = AudioManager.OnModeChangedListener { mode ->
                if (mode == AudioManager.MODE_NORMAL) main.postDelayed({ restartRecognition() }, 500)
            }
            audio.addOnModeChangedListener({ main.post(it) }, listener)
            modeListener = listener
        }
    }

    private fun removeAudioObservers() {
        if (!this::context.isInitialized) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        deviceCallback?.let { audio.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { runCatching { audio.removeOnModeChangedListener(it) } }
        }
        modeListener = null
    }

    private fun restartRecognition() {
        if (state != State.Running) return
        destroyRecognizer()
        commitLive()
        errorCount = 0
        runCatching { listen() }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(1000)
                // 识别器一直报音量；超过 4 秒没动静说明卡住了（有的识别器从来不报音量，就不管）
                if (state == State.Running && levelsSeen && SystemClock.elapsedRealtime() - lastAudioAt > 4000) restartRecognition()
            }
        }
    }

    private fun stopAudio() {
        watchdog?.cancel()
        watchdog = null
        removeAudioObservers()
        levels = List(levels.size) { 0f }
    }

    private companion object {
        // 安卓 12 起才有的错误码，写成数字免得低版本找不到
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
