package com.yishulabs.qtranslator.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yishulabs.qtranslator.analytics.Analytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Locale

/** 要朗读的一段话。同一段话再点一次就是停止。 */
data class Speech(val text: String, val isChinese: Boolean, val accent: Int) {
    companion object {
        fun english(text: String, accent: Int? = null) = Speech(text, false, accent ?: Prefs.accent)
        fun chinese(text: String) = Speech(text, true, 0)
        fun text(text: String, isChinese: Boolean) = if (isChinese) chinese(text) else english(text)
    }
}

/** 设置页里列出的一个系统音色（TextToSpeech 的 Voice） */
data class SystemVoice(
    /** Voice 的名字，存在设置里 */
    val name: String,
    val title: String,
    val detail: String,
    /** “高级”“增强”“基础”；还没下载时是 null */
    val qualityTag: String?,
    val installed: Boolean,
    val needsNetwork: Boolean,
)

/**
 * 发音：英文单词和短语用有道真人发音（区分英/美音）；句子用选的音色：AI 音色或系统音色。
 * 所有朗读都按设置里的全局速度。
 */
object Speaker {
    /** 正在朗读的内容；界面据此把“朗读”按钮换成“停止” */
    var playing by mutableStateOf<Speech?>(null)
        private set

    /** 系统语音合成准备好了：设置页这时才能列出系统音色 */
    var ttsReady by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var aiJob: Job? = null
    /** 当前这段系统朗读的编号；被新的一段打断的旧回调不算 */
    private var utterance = 0
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    /** 可选的朗读速度（倍数，1 是正常速度） */
    val speeds = listOf(0.6f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)

    fun speedLabel(speed: Float) =
        if (speed == 1f) "正常" else BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "×"

    /** 全局朗读速度：系统音色、AI 音色和有道真人发音都按这个速度 */
    val speed: Float get() = Prefs.playbackSpeed.takeIf { it > 0f } ?: 1f

    fun init(context: Context) {
        AIVoice.init(context)
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post { ttsReady = status == TextToSpeech.SUCCESS }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
        }
    }

    private fun finished(utteranceId: String?) {
        main.post { if (utteranceId == utterance.toString()) playing = null }
    }

    /** 没在读这段就开始读；正在读这段就停下 */
    fun toggle(speech: Speech) {
        if (playing == speech) {
            stop()
        } else {
            val voice = when {
                !speech.isChinese && isWordLike(speech.text) -> "youdao"
                AIVoice.selected != null && AIVoice.apiKey != null -> "ai"
                else -> "system"
            }
            Analytics.track(Analytics.Event.SPEAK, mapOf("voice" to voice, "language" to if (speech.isChinese) "zh" else "en"))
            play(speech)
        }
    }

    fun play(speech: Speech) {
        stop()
        playing = speech
        // 英文单词和短语用有道真人发音；句子用选的音色：AI 音色或系统音色
        val aiVoice = AIVoice.selected
        when {
            !speech.isChinese && isWordLike(speech.text) -> playOnline(speech)
            aiVoice != null && AIVoice.apiKey != null -> startAI(speech, aiVoice)
            else -> synthesize(speech)
        }
    }

    /** 用某个 AI 音色读一段（设置里试听也用它） */
    fun playAI(speech: Speech, voice: String) {
        stop()
        playing = speech
        startAI(speech, voice)
    }

    /** 设置里试听系统音色：用指定的音色读一句 */
    fun preview(text: String, isChinese: Boolean, voice: String) {
        stop()
        val speech = Speech(text, isChinese, if (isChinese) 0 else 2)
        playing = speech
        synthesize(speech, voice)
    }

    fun stop() {
        aiJob?.cancel()
        aiJob = null
        player?.release()
        player = null
        utterance++
        tts?.stop()
        playing = null
    }

    private fun isWordLike(text: String) =
        text.length <= 40 && text.split(" ").size <= 4 && text.none { it in ".!?,;\n" }

    private fun startAI(speech: Speech, voice: String) {
        aiJob = scope.launch {
            val file = runCatching { AIVoice.speak(speech.text, voice) }.getOrNull()
            if (!isActive || playing != speech) return@launch
            // 网络或 Key 有问题：改用系统音色
            if (file == null) synthesize(speech) else playMedia(speech, file.path)
        }
    }

    private fun playOnline(speech: Speech) {
        val url = "https://dict.youdao.com/dictvoice?" + Http.query("audio" to speech.text, "type" to speech.accent.toString())
        playMedia(speech, url)
    }

    /** 播放在线发音或 AI 音色的文件；拿不到时退回系统语音 */
    private fun playMedia(speech: Speech, source: String) {
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        media.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            it.start()
            if (speed != 1f) runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) }
        }
        media.setOnCompletionListener { if (player === it && playing == speech) stop() }
        media.setOnErrorListener { mp, _, _ ->
            if (player === mp && playing == speech) {
                mp.release()
                player = null
                synthesize(speech)
            }
            true
        }
        try {
            media.setDataSource(source)
            media.prepareAsync()
        } catch (e: Exception) {
            player = null
            media.release()
            synthesize(speech)
        }
    }

    /** 系统语音合成：设置里选了音色就用它（chosen 是试听时指定的），没选按口音用系统默认 */
    private fun synthesize(speech: Speech, chosen: String? = null) {
        val engine = tts ?: return run { playing = null }
        val name = chosen ?: if (speech.isChinese) Prefs.voiceChinese else Prefs.voiceEnglish
        val voice = resolve(name)
        val usedChosen = voice != null && runCatching { engine.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
        if (!usedChosen) {
            engine.language = when {
                speech.isChinese -> Locale.SIMPLIFIED_CHINESE
                speech.accent == 1 -> Locale.UK
                else -> Locale.US
            }
        }
        engine.setSpeechRate(speed)
        val id = (++utterance).toString()
        if (engine.speak(speech.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) playing = null
    }

    // 系统音色

    private fun voices(): List<Voice> = runCatching { tts?.voices?.toList() }.getOrNull().orEmpty()

    private fun Voice.isInstalled() = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (features ?: emptySet())

    /** 去掉 -local / -network 后缀：谷歌的同一个音色有本机和联网两个版本 */
    private fun baseName(name: String) = name.removeSuffix("-local").removeSuffix("-network")

    /** 设置里存的音色名字：先找同名的，找不到就找同一个音色的另一个版本；没下载的不用 */
    private fun resolve(name: String): Voice? {
        if (name.isEmpty()) return null
        val all = voices()
        return (all.firstOrNull { it.name == name } ?: all.firstOrNull { baseName(it.name) == baseName(name) })
            ?.takeIf { it.isInstalled() }
    }

    /** 已知的谷歌音色是男声还是女声（名字里只有代号，看不出来） */
    private val genders = mapOf(
        "sfg" to "女声", "iob" to "女声", "iog" to "女声", "tpc" to "女声", "tpf" to "女声", "iol" to "男声", "iom" to "男声", "tpd" to "男声",
        "gba" to "女声", "gbc" to "女声", "gbg" to "女声", "gbb" to "男声", "gbd" to "男声", "rjs" to "男声",
        "ccc" to "女声", "ccd" to "男声",
    )
    private val googleName = Regex("^[a-z]{2,3}-[a-z]{2}-x-([a-z]{3})-(local|network)$")

    /**
     * 精选的系统音色：只列美式、英式英语和普通话，同一个音色只留一个版本，
     * 去掉音质低的，已下载、音质好的排前面，每种语言最多 6 个。
     */
    fun systemVoices(chinese: Boolean): List<SystemVoice> {
        if (!ttsReady) return emptyList()
        val matching = voices().filter { voice ->
            val locale = voice.locale
            if (chinese) {
                (locale.language == "zh" || locale.language == "cmn") && (locale.country == "CN" || locale.country.isEmpty())
            } else {
                locale.language == "en" && (locale.country == "US" || locale.country == "GB")
            } && voice.quality >= Voice.QUALITY_NORMAL
        }
        // 谷歌每种语言还有一个“-language”的默认音色，和具体的音色重复；只有它时才保留
        val named = matching.filterNot { it.name.endsWith("-language") }.ifEmpty { matching }
        return named.groupBy { baseName(it.name) }.values
            .map { group -> group.sortedWith(compareBy({ !it.isInstalled() }, { it.isNetworkConnectionRequired })).first() }
            .sortedWith(compareBy({ !it.isInstalled() }, { -it.quality }, { it.name }))
            .take(6)
            .map { voice ->
                val code = googleName.find(voice.name)?.groupValues?.get(1)
                val accent = when {
                    chinese -> "普通话"
                    voice.locale.country == "GB" -> "英式"
                    else -> "美式"
                }
                val installed = voice.isInstalled()
                SystemVoice(
                    name = voice.name,
                    title = code?.uppercase() ?: voice.name,
                    detail = listOfNotNull(accent, code?.let { genders[it] }).joinToString(" · "),
                    qualityTag = when {
                        !installed -> null
                        voice.quality >= Voice.QUALITY_VERY_HIGH -> "高级"
                        voice.quality >= Voice.QUALITY_HIGH -> "增强"
                        else -> "基础"
                    },
                    installed = installed,
                    needsNetwork = voice.isNetworkConnectionRequired,
                )
            }
    }

    /** 设置里选的音色是不是这个（存的可能是同一个音色的另一个版本） */
    fun isSameVoice(stored: String, voice: SystemVoice) = stored.isNotEmpty() && baseName(stored) == baseName(voice.name)
}
