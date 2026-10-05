package com.yishulabs.qtranslator.analytics

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure
import com.yishulabs.qtranslator.BuildConfig
import kotlinx.coroutines.delay

/**
 * 匿名使用统计（友盟+）。只统计用了哪些功能，不上报输入的文字、图片和录音。
 * 用户同意后才初始化、才上报；没有 AppKey 时什么都不做。事件和苹果版一致（apple/Config/umeng-events.csv）。
 */
object Analytics {
    /** 自定义事件。事件 ID 要先在友盟后台“自定义事件”里添加，名称用 title */
    enum class Event(val id: String, val title: String) {
        WORD_LOOKUP("word_lookup", "查单词"),
        TEXT_TRANSLATE("text_translate", "翻译句子"),
        IMAGE_TRANSLATE("image_translate", "图片翻译"),
        IMAGE_ROTATE("image_rotate", "旋转图片"),
        IMAGE_RECOGNIZE_AGAIN("image_recognize_again", "图片重新识别"),
        VOICE_INPUT("voice_input", "语音输入"),
        AI_OPTIMIZE("ai_optimize", "AI 优化"),
        REPLY_WRITE("reply_write", "写回复"),
        SPEAK("speak", "朗读"),
        WORD_STAR("word_star", "加入生词本"),
        SESSION_NEW("session_new", "新建会话"),
        SCENE_NEW("scene_new", "新建场景"),
        MODULE_OPEN("module_open", "打开模块"),
        QUICK_ACTION("quick_action", "主屏快捷操作"),
        INTERPRET_START("interpret_start", "开始同声传译"),
        INTERPRET_FINISH("interpret_finish", "结束同声传译"),
        INTERPRET_SWITCH("interpret_switch", "传译切换语言"),
        INTERPRET_MINIMIZE("interpret_minimize", "传译收起到后台"),
        INTERPRET_SUMMARY("interpret_summary", "传译 AI 要点"),
        FACE_START("face_start", "开始面对面对话"),
        FACE_FINISH("face_finish", "结束面对面对话"),
        PRACTICE_START("practice_start", "开始场景练习"),
        PRACTICE_REPLY("practice_reply", "练习中回答一句"),
        PRACTICE_FINISH("practice_finish", "结束场景练习"),
        PRACTICE_HANDS_FREE("practice_hands_free", "切换语音聊天"),
        SETTINGS_AI_PROVIDER("settings_ai_provider", "设置 AI 服务商"),
        SETTINGS_VOICE("settings_voice", "选择音色"),
        SETTINGS_SPEED("settings_speed", "调整朗读速度"),
    }

    private const val OFF_KEY = "privacy.statsOff.v1"
    private lateinit var store: SharedPreferences

    /** 统计默认开启；用户可以在“设置 → 关于 → 隐私协议”里自己关闭 */
    var accepted by mutableStateOf(true)
        private set

    /** 这个版本能发统计（编译时填了友盟 AppKey） */
    val isAvailable: Boolean get() = UmengBridge.isAvailable

    fun init(context: Context) {
        store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        accepted = !store.getBoolean(OFF_KEY, false)
        UmengBridge.preInit(context)
        if (accepted) UmengBridge.start(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        accepted = on
        store.edit().putBoolean(OFF_KEY, !on).apply()
        if (on) UmengBridge.start(context) else UmengBridge.stop()
    }

    /** 记录一次事件。属性只放功能相关的分类（方向、来源、档位），不放用户输入的内容 */
    fun track(event: Event, attributes: Map<String, String> = emptyMap()) {
        if (accepted) UmengBridge.event(event.id, attributes)
    }

    /** 页面开始和结束（友盟的页面统计） */
    fun pageStart(name: String) {
        if (accepted) UmengBridge.pageStart(name)
    }

    fun pageEnd(name: String) {
        if (accepted) UmengBridge.pageEnd(name)
    }

    /** 把数字分成几档，统计里看分布，不记具体数字 */
    fun bucket(value: Int, edges: List<Int>): String {
        edges.forEachIndexed { i, edge ->
            if (value <= edge) return if (i == 0) "≤$edge" else "${edges[i - 1] + 1}-$edge"
        }
        return ">${edges.lastOrNull() ?: 0}"
    }

    fun direction(fromChinese: Boolean) = if (fromChinese) "zh2en" else "en2zh"
}

/** 页面统计：显示时开始计时，离开时结束（对应苹果版的 .analyticsPage） */
@Composable
fun AnalyticsPage(name: String) {
    DisposableEffect(name) {
        Analytics.pageStart(name)
        onDispose { Analytics.pageEnd(name) }
    }
}

/** 隐私协议全文。同样的内容在 docs/PRIVACY.md 里 */
val privacySections = listOf(
    "我们收集什么" to "为了了解哪些功能有用、把快译做得更好，快译会收集匿名的使用数据：你使用了哪些功能（例如查词、翻译、拍照翻译、同声传译、面对面对话、场景练习）、功能的使用时长和次数、你的设备型号、系统版本和 App 版本。这些数据不能识别你是谁。",
    "我们不收集什么" to "你输入或说出的内容、翻译的文字、拍摄或选择的图片、录音、会话和记录的内容、生词本、你的 API Key，都不会上传给我们或统计服务商。",
    "数据交给谁处理" to "使用数据由友盟+（Umeng）提供的统计服务处理，详见友盟+的隐私政策。快译没有自己的服务器。",
    "你的内容在哪里" to "会话、图片、录音和各种记录只保存在你的设备上，删除 App 就会一起删除。翻译和 AI 功能会按你的选择，把需要翻译的文字发给翻译服务或你自己填写的 AI 服务商。",
    "你的选择" to "快译默认统计匿名使用数据。你可以随时在下面关闭，关闭后快译的全部功能照常使用，只是不再统计。",
)

@Composable
private fun PrivacyText() {
    androidx.compose.foundation.layout.Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        privacySections.forEach { (title, body) ->
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 设置里查看隐私协议，并可以自己关闭统计 */
@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐私协议") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f, fill = false)) { PrivacyText() }
                if (Analytics.isAvailable) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        Text("发送匿名使用统计", fontWeight = FontWeight.Bold)
                        androidx.compose.material3.Switch(Analytics.accepted, { Analytics.setEnabled(context, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}

/** 根界面调用一次：统计根界面正在看的页面（翻译，或者某个模块的首页） */
@Composable
fun AnalyticsConsentPrompt(page: String) {
    AnalyticsPage(page)
}

/**
 * 和友盟 SDK 打交道的唯一入口。
 * 启动时只 preInit（按友盟的合规要求，不采集任何信息）；用户同意后才 init、才上报。
 * AppKey 在编译时从 android/local.properties 读进 BuildConfig，平板和手机分开统计，没填平板的就用手机的。
 */
internal object UmengBridge {
    private const val CHANNEL = "Android"
    private lateinit var appContext: Context
    private var key = ""
    private var started = false
    /** 用户关掉了统计：这次运行里不再上报，重新打开要等下次启动 */
    private var disabled = false

    val isAvailable: Boolean get() = key.isNotEmpty()

    private fun appKey(context: Context): String {
        val phone = BuildConfig.UMENG_APPKEY.trim()
        val tablet = BuildConfig.UMENG_APPKEY_TABLET.trim()
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
        return if (isTablet && tablet.isNotEmpty()) tablet else phone
    }

    fun preInit(context: Context) {
        appContext = context.applicationContext
        key = appKey(appContext)
        if (!isAvailable) return
        UMConfigure.setLogEnabled(BuildConfig.DEBUG)
        UMConfigure.preInit(appContext, key, CHANNEL)
    }

    fun start(context: Context) {
        if (!isAvailable || started) return
        started = true
        UMConfigure.submitPolicyGrantResult(appContext, true)
        // 只有一个 Activity，页面由界面手动报告（翻译、设置、各模块）
        MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.MANUAL)
        // 初始化要读写文件，放到后台线程
        Thread { UMConfigure.init(appContext, key, CHANNEL, UMConfigure.DEVICE_TYPE_PHONE, "") }.start()
    }

    fun stop() {
        if (!started || disabled) return
        disabled = true
        UMConfigure.submitPolicyGrantResult(appContext, false)
        MobclickAgent.disable()
    }

    private val enabled get() = started && !disabled

    fun event(id: String, attributes: Map<String, String>) {
        if (!enabled) return
        if (attributes.isEmpty()) MobclickAgent.onEvent(appContext, id) else MobclickAgent.onEvent(appContext, id, attributes)
    }

    fun pageStart(name: String) {
        if (enabled) MobclickAgent.onPageStart(name)
    }

    fun pageEnd(name: String) {
        if (enabled) MobclickAgent.onPageEnd(name)
    }
}
