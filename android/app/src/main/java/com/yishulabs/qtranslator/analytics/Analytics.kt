package com.yishulabs.qtranslator.analytics

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
}

/**
 * 和友盟 SDK 打交道的唯一入口。现在是空实现：接入友盟 SDK 时只改这里。
 */
internal object UmengBridge {
    val isAvailable: Boolean get() = false
    fun preInit(context: Context) {}
    fun start(context: Context) {}
    fun stop() {}
    fun event(id: String, attributes: Map<String, String>) {}
    fun pageStart(name: String) {}
    fun pageEnd(name: String) {}
}
