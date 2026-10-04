package com.yishulabs.qtranslator

import android.app.Application
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.UsageStore
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.HistoryStore
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.SecretStore
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.modules.ModuleStore

class QTranslatorApp : Application() {
    /** 整个 app 只有一个会话控制器，旋转屏幕、切换窗口大小时不重建 */
    val controller: ConversationController by lazy { ConversationController(this) }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        SecretStore.init(this)
        AISettings.init(this)
        UsageStore.init(this)
        HistoryStore.init(this)
        Speaker.init(this)
        VoiceInput.init(this)
        com.yishulabs.qtranslator.core.AudioReplay.init(this)
        ModuleStore.init(this)
        Analytics.init(this)
    }
}
