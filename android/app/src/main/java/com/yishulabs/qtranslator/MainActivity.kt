package com.yishulabs.qtranslator

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.pm.ShortcutManagerCompat
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.modules.AppModule
import com.yishulabs.qtranslator.modules.ModuleRouter
import com.yishulabs.qtranslator.ui.QTranslatorRoot
import com.yishulabs.qtranslator.ui.loadBitmap

class MainActivity : ComponentActivity() {
    companion object {
        /** 桌面长按图标的快捷方式（res/xml/shortcuts.xml），extra 里是类型：interpret / face / practice / new */
        const val ACTION_SHORTCUT = "com.yishulabs.qtranslator.SHORTCUT"
        const val EXTRA_SHORTCUT = "type"
    }

    private val controller get() = (application as QTranslatorApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QTranslatorRoot(controller) }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** 从别的 app 分享过来的文字、图片，或选中文字后点“快译”：放进当前会话翻译；桌面快捷方式打开对应功能 */
    private fun handle(intent: Intent?) {
        intent ?: return
        // TODO(merge): 在这里加上 `if (InterpretService.handleOpen(intent)) return`（modules/InterpretService.kt 在另一个分支里）
        when (intent.action) {
            ACTION_SHORTCUT -> intent.getStringExtra(EXTRA_SHORTCUT)?.let { handleShortcut(it) }
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.let { controller.sendText(it.toString()) }
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    val bitmap: Bitmap? = uri?.let { loadBitmap(this, it) }
                    if (bitmap != null) controller.sendImages(listOf(bitmap))
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { controller.sendText(it) }
                }
            }
        }
    }

    /** 桌面快捷方式：同声传译、面对面对话直接开始；场景练习打开首页选场景；新建翻译开一个新会话 */
    private fun handleShortcut(type: String) {
        Analytics.track(Analytics.Event.QUICK_ACTION, mapOf("type" to type))
        ShortcutManagerCompat.reportShortcutUsed(this, type)
        when (type) {
            "interpret" -> ModuleRouter.open(AppModule.INTERPRET, start = true, from = "shortcut")
            "face" -> ModuleRouter.open(AppModule.FACE, start = true, from = "shortcut")
            "practice" -> ModuleRouter.open(AppModule.PRACTICE, from = "shortcut")
            "new" -> {
                ModuleRouter.close()
                ModuleRouter.newSessionRequested = true
            }
        }
    }
}
