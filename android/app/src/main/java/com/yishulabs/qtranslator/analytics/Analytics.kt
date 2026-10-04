package com.yishulabs.qtranslator.analytics

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.AlertDialog
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

    private const val CONSENT_KEY = "analytics.consent"
    private lateinit var store: SharedPreferences

    /** 用户的选择：null 还没问过，true 同意，false 不同意 */
    var consent by mutableStateOf<Boolean?>(null)
        private set

    /** 这个版本能发统计（编译时填了友盟 AppKey） */
    val isAvailable: Boolean get() = UmengBridge.isAvailable

    fun init(context: Context) {
        store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        consent = if (store.contains(CONSENT_KEY)) store.getBoolean(CONSENT_KEY, false) else null
        UmengBridge.preInit(context)
        if (consent == true) UmengBridge.start(context)
    }

    fun updateConsent(context: Context, value: Boolean) {
        consent = value
        store.edit().putBoolean(CONSENT_KEY, value).apply()
        if (value) UmengBridge.start(context) else UmengBridge.stop()
    }

    /** 记录一次事件。属性只放功能相关的分类（方向、来源、档位），不放用户输入的内容 */
    fun track(event: Event, attributes: Map<String, String> = emptyMap()) {
        if (consent == true) UmengBridge.event(event.id, attributes)
    }

    /** 页面开始和结束（友盟的页面统计） */
    fun pageStart(name: String) {
        if (consent == true) UmengBridge.pageStart(name)
    }

    fun pageEnd(name: String) {
        if (consent == true) UmengBridge.pageEnd(name)
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

/**
 * 第一次打开时问一次要不要发送匿名使用统计（没有统计功能的版本不问）。
 * 根界面调用一次；顺便统计根界面正在看的页面：翻译，或者某个模块的首页。
 */
@Composable
fun AnalyticsConsentPrompt(page: String) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!Analytics.isAvailable || Analytics.consent != null) return@LaunchedEffect
        delay(1000)
        asking = true
    }
    AnalyticsPage(page)
    if (asking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("帮助改进快译") },
            text = { Text("允许发送匿名使用统计吗？只统计用了哪些功能（比如查词、拍照翻译、同声传译），不包含你输入的文字、图片和录音。统计由友盟+处理，随时可以在设置里关闭。") },
            confirmButton = {
                TextButton(onClick = { asking = false; Analytics.updateConsent(context, true) }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { asking = false; Analytics.updateConsent(context, false) }) { Text("不允许") }
            },
        )
    }
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
