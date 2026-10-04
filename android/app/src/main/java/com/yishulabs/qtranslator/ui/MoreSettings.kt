package com.yishulabs.qtranslator.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.AIVoice
import com.yishulabs.qtranslator.core.OfflineTranslator
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.core.SystemVoice
import com.yishulabs.qtranslator.core.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val Green = Color(0xFF2E9E5B)
private val Orange = Color(0xFFE08A00)

/** 打开系统设置里的某一页；打不开就退回到系统设置首页 */
private fun openSettings(context: Context, vararg actions: String) {
    for (action in listOf(*actions, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
}

/** 回到这个页面时（例如从系统设置回来）执行一次 */
@Composable
private fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

// 朗读速度

/** 全局朗读速度：设置页和朗读声音页都能调 */
@Composable
fun SpeechSpeedRow(onChange: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        SettingRow("朗读速度", value = Speaker.speedLabel(Prefs.playbackSpeed), onClick = { menu = true })
        DropdownMenu(menu, { menu = false }) {
            Speaker.speeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(Speaker.speedLabel(speed)) },
                    leadingIcon = { if (speed == Prefs.playbackSpeed) Icon(Icons.Rounded.Check, null, tint = Lx.colors.accent) },
                    onClick = {
                        menu = false
                        if (speed == Prefs.playbackSpeed) return@DropdownMenuItem
                        Prefs.updatePlaybackSpeed(speed)
                        Analytics.track(Analytics.Event.SETTINGS_SPEED, mapOf("speed" to Speaker.speedLabel(speed)))
                        onChange()
                    },
                )
            }
        }
    }
}

// 朗读声音

private const val ENGLISH_SAMPLE = "Hi, nice to meet you. How are you doing today?"
private const val CHINESE_SAMPLE = "你好，很高兴认识你，今天过得怎么样？"

/** 朗读的音色和语速：AI 音色（更像真人，要 ChatGPT 的 Key）或精选的系统音色，选中就读一句试听 */
@Composable
fun SpeechVoicesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var hint by remember { mutableStateOf<String?>(null) }
    val hasKey = remember { AIVoice.apiKey != null }
    // 从系统设置下载了音色回来，重新列一遍
    var refresh by remember { mutableIntStateOf(0) }
    OnResume { refresh++ }
    DisposableEffect(Unit) { onDispose { Speaker.stop() } }

    /** 改了语速：用现在选的音色读一句听听效果 */
    fun playCurrent() {
        val ai = AIVoice.selected
        if (ai != null && hasKey) Speaker.playAI(Speech.english(ENGLISH_SAMPLE), ai)
        else Speaker.preview(ENGLISH_SAMPLE, false, Prefs.voiceEnglish)
    }

    SettingsPageHeader("朗读声音", onBack)

    Group(null) { SpeechSpeedRow(onChange = ::playCurrent) }
    Footnote("所有朗读都按这个速度：AI 音色、系统音色和查单词时的真人发音。")

    // AI 音色
    Group("AI 音色（最像真人）") {
        VoiceRow("不用 AI 音色", "用下面的系统音色", selected = Prefs.aiVoice.isEmpty(), tag = null) {
            Prefs.updateAIVoice("")
            Speaker.stop()
        }
        AIVoice.voices.forEach { (id, detail) ->
            SettingsDivider()
            VoiceRow(
                id.replaceFirstChar { it.uppercase() }, detail, selected = Prefs.aiVoice == id, tag = null,
                modifier = Modifier.alpha(if (hasKey) 1f else 0.45f),
            ) {
                if (!hasKey) {
                    hint = "AI 音色要先在设置的“AI 增强”里选 ChatGPT 并填写 API Key。"
                    return@VoiceRow
                }
                hint = null
                Prefs.updateAIVoice(id)
                Analytics.track(Analytics.Event.SETTINGS_VOICE, mapOf("type" to "ai", "voice" to id))
                Speaker.playAI(Speech.text("$ENGLISH_SAMPLE $CHINESE_SAMPLE", false), id)
            }
        }
    }
    Footnote(
        if (!hasKey) "要先在设置的“AI 增强”里选 ChatGPT 并填写 API Key。AI 音色需要联网，按字数计费，读一句大约不到 1 分钱。"
        else "中英文都能读。需要联网，按字数计费，读一句大约不到 1 分钱；读过的句子会缓存，再读不收费。网络不好时自动改用系统音色。"
    )

    // 系统音色
    val english = remember(refresh, Speaker.ttsReady) { Speaker.systemVoices(chinese = false) }
    val chinese = remember(refresh, Speaker.ttsReady) { Speaker.systemVoices(chinese = true) }

    @Composable
    fun systemRows(voices: List<SystemVoice>, chineseVoices: Boolean) {
        if (voices.isEmpty()) {
            SettingRow(if (Speaker.ttsReady) "这台手机还没有这种语言的系统音色" else "正在读取系统音色…")
            return
        }
        val stored = if (chineseVoices) Prefs.voiceChinese else Prefs.voiceEnglish
        voices.forEachIndexed { index, voice ->
            if (index > 0) SettingsDivider()
            val tag = when (voice.qualityTag) {
                "高级" -> "高级" to Green
                "增强" -> "增强" to Lx.colors.accent
                null -> "需下载" to Orange
                else -> "基础" to Lx.colors.ink3
            }
            val detail = if (voice.needsNetwork) voice.detail + " · 需联网" else voice.detail
            VoiceRow(voice.title, detail, selected = Speaker.isSameVoice(stored, voice), tag = tag) {
                if (!voice.installed) {
                    val language = if (chineseVoices) "中文" else "英语"
                    hint = "“${voice.title}”还没有下载：点下面的“去系统设置下载更多音色”，在${language}里下载这个音色，下载后回到这里再选。"
                    return@VoiceRow
                }
                hint = null
                if (chineseVoices) Prefs.updateVoiceChinese(voice.name) else Prefs.updateVoiceEnglish(voice.name)
                Analytics.track(Analytics.Event.SETTINGS_VOICE, mapOf("type" to "system", "voice" to voice.title))
                Speaker.preview(if (chineseVoices) CHINESE_SAMPLE else ENGLISH_SAMPLE, chineseVoices, voice.name)
            }
        }
    }

    Group("英文系统音色") { systemRows(english, false) }
    Group("中文系统音色") { systemRows(chinese, true) }
    Footnote(
        hint ?: ("系统音色免费、离线。标“需下载”的音色要先到系统的朗读声音设置里下载（选音质高的版本更好听），下载后回到这里就能选。" +
            "标“需联网”的音色朗读时要联网。")
    )

    Group(null) {
        SettingRow("去系统设置下载更多音色", onClick = {
            openSettings(context, TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, "com.android.settings.TTS_SETTINGS")
        }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Lx.colors.accent) }
    }
    Footnote("打开后选语言，下载想要的音色。如果打开的是“文字转语音输出”，点首选引擎旁边的设置图标，再选“安装语音数据”。")
}

/** 音色的一行：左边勾选，中间名字和说明，右边标签和喇叭；点一下就选中并试听 */
@Composable
private fun VoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    tag: Pair<String, Color>?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Lx.colors
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.Check, if (selected) "已选择" else null, tint = colors.accent,
            modifier = Modifier.size(18.dp).alpha(if (selected) 1f else 0f),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.ink, fontSize = 16.sp)
            Text(detail, color = colors.ink3, fontSize = 12.sp)
        }
        tag?.let { (label, color) ->
            Text(
                label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = colors.accent, modifier = Modifier.size(20.dp))
    }
}

// 离线模型

private sealed interface ModelState {
    data object Checking : ModelState
    data object Installed : ModelState
    data object Available : ModelState
    data object Downloading : ModelState
    data object Unsupported : ModelState
    data class Failed(val message: String) : ModelState
}

private val chineseModel = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
private val speechLocales = listOf("en-US" to "英语", "zh-CN" to "中文")

/** 离线模型：中英翻译模型（ML Kit）、系统语音识别的离线语音包。下载后不用联网，速度也更快。 */
@Composable
fun OfflineModelsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translation by remember { mutableStateOf<ModelState>(ModelState.Checking) }
    var confirmDelete by remember { mutableStateOf(false) }
    val speech = remember { mutableStateMapOf<String, ModelState>() }
    // 触发下载用的本机识别器，离开页面时释放
    val downloader = remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(Unit) { onDispose { downloader.value?.destroy() } }

    suspend fun refreshTranslation() {
        if (translation != ModelState.Downloading) {
            translation = if (OfflineTranslator.isReady()) ModelState.Installed else ModelState.Available
        }
    }

    suspend fun refreshSpeech() {
        val support = if (Build.VERSION.SDK_INT >= 33) recognitionSupport(context) else null
        speechLocales.forEach { (id, _) ->
            speech[id] = when {
                support == null -> ModelState.Unsupported
                support.installedOnDeviceLanguages.any { matches(it, id) } -> ModelState.Installed
                support.pendingOnDeviceLanguages.any { matches(it, id) } -> ModelState.Downloading
                support.supportedOnDeviceLanguages.any { matches(it, id) } ->
                    if (speech[id] == ModelState.Downloading) ModelState.Downloading else ModelState.Available
                else -> ModelState.Unsupported
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshTranslation()
        refreshSpeech()
    }
    // 语音包在后台下载：每几秒看一下好了没有
    val speechDownloading = speech.values.any { it == ModelState.Downloading }
    LaunchedEffect(speechDownloading) {
        repeat(60) {
            if (!speechDownloading) return@LaunchedEffect
            delay(4000)
            refreshSpeech()
        }
    }
    OnResume { scope.launch { refreshSpeech() } }

    fun downloadTranslation() {
        translation = ModelState.Downloading
        scope.launch {
            translation = try {
                RemoteModelManager.getInstance().download(chineseModel, DownloadConditions.Builder().build()).await()
                ModelState.Installed
            } catch (e: Exception) {
                ModelState.Failed("下载失败")
            }
        }
    }

    fun downloadSpeech(id: String) {
        if (Build.VERSION.SDK_INT < 33) return
        speech[id] = ModelState.Downloading
        try {
            val recognizer = downloader.value ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { downloader.value = it }
            recognizer.triggerModelDownload(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, id)
            )
        } catch (e: Exception) {
            speech[id] = ModelState.Failed("下载失败")
        }
    }

    SettingsPageHeader("离线模型", onBack)

    Group("翻译模型") {
        ModelRow("中英翻译模型", "中 ⇄ 英，约 30 MB", translation, onDownload = ::downloadTranslation, onDelete = { confirmDelete = true })
    }
    Footnote("句子、段落、图片、同声传译和面对面对话都会优先用本机翻译：不用联网、不限量，比在线翻译快很多。英文模型手机里自带，只需要下载中文模型。")

    Group("语音识别模型") {
        speechLocales.forEachIndexed { index, (id, name) ->
            if (index > 0) SettingsDivider()
            ModelRow(name, null, speech[id] ?: ModelState.Checking, onDownload = { downloadSpeech(id) })
        }
        SettingsDivider()
        SettingRow("去系统设置管理离线语音包", onClick = { openSettings(context, Settings.ACTION_VOICE_INPUT_SETTINGS) }) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Lx.colors.accent)
        }
    }
    Footnote(
        "语音输入、同声传译和面对面对话用系统的语音识别。下载离线语音包后在本机识别，更准、延迟更低，录音不上传。" +
            "显示“这台设备不支持”时，可以到系统的语音输入设置里下载离线语音包（不同手机位置不同，一般在“Google 语音输入 → 离线语音识别”）。"
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除中英翻译模型？") },
            text = { Text("删除后翻译句子会改用在线翻译，需要时可以再下载。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    translation = ModelState.Checking
                    scope.launch {
                        runCatching { RemoteModelManager.getInstance().deleteDownloadedModel(chineseModel).await() }
                        refreshTranslation()
                    }
                }) { Text("删除", color = Color(0xFFD93025)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ModelRow(title: String, subtitle: String?, state: ModelState, onDownload: () -> Unit, onDelete: (() -> Unit)? = null) {
    val colors = Lx.colors
    SettingRow(title, subtitle = subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ModelState.Checking, ModelState.Downloading -> {
                    if (state == ModelState.Downloading) Text("正在下载", color = colors.ink3, fontSize = 14.sp)
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                ModelState.Installed -> {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
                    Text("已下载", color = Green, fontSize = 15.sp)
                    onDelete?.let {
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = colors.accent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = it).padding(4.dp))
                    }
                }
                ModelState.Available ->
                    Text("下载", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onDownload).padding(4.dp))
                ModelState.Unsupported -> Text("这台设备不支持", color = colors.ink3, fontSize = 14.sp)
                is ModelState.Failed -> {
                    Text(state.message, color = colors.ink3, fontSize = 14.sp)
                    Text("重试", color = colors.accent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onDownload).padding(4.dp))
                }
            }
        }
    }
}

/** 识别服务返回的语言标签和我们要的是不是同一种（en-US / zh-CN，中文可能写成 cmn-Hans-CN） */
private fun matches(tag: String, id: String): Boolean {
    val t = tag.lowercase().replace('_', '-')
    return if (id == "zh-CN") t in setOf("zh-cn", "cmn-hans-cn", "zh-hans-cn", "cmn-cn", "zh-hans") else t == "en-us"
}

/** 问本机识别服务：哪些语言已下载、正在下载、可以下载（安卓 13 起才有） */
@RequiresApi(33)
private suspend fun recognitionSupport(context: Context): RecognitionSupport? {
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
    return suspendCancellableCoroutine { continuation ->
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                recognizer.destroy()
                if (continuation.isActive) continuation.resume(support)
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }
}
