package com.yishulabs.qtranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yishulabs.qtranslator.analytics.AnalyticsConsentPrompt
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.modules.ModuleRouter
import com.yishulabs.qtranslator.ui.modules.ModulePage
import kotlinx.coroutines.launch

/** 根界面弹出的面板 */
sealed interface RootSheet {
    data object Settings : RootSheet
    data object NewSession : RootSheet
    data object NewScene : RootSheet
    data class EditScene(val id: String) : RootSheet
    data object Starred : RootSheet
}

@Composable
fun QTranslatorRoot(controller: ConversationController) {
    QTranslatorTheme {
        var sheet by remember { mutableStateOf<RootSheet?>(null) }
        // 第一次打开时问要不要发送匿名统计；统计根界面在看翻译还是哪个模块的首页
        AnalyticsConsentPrompt(ModuleRouter.module?.let { it.fullTitle + "首页" } ?: "翻译")
        // 桌面快捷方式“新建翻译”
        LaunchedEffect(ModuleRouter.newSessionRequested) {
            if (!ModuleRouter.newSessionRequested) return@LaunchedEffect
            ModuleRouter.newSessionRequested = false
            sheet = null
            controller.newSession()
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Lx.colors.background)) {
            val width = maxWidth
            // 平板横屏和大屏：左边常驻会话列表；输入记录在宽度够时放在右边一栏
            if (width >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    DrawerContent(controller, onSelect = {}, onSheet = { sheet = it }, modifier = Modifier.width(300.dp).fillMaxHeight())
                    VerticalDivider(color = Lx.colors.line)
                    val module = ModuleRouter.module
                    if (module != null) {
                        ModulePage(module, controller, wide = true)
                    } else {
                        ConversationScreen(
                            controller, wide = true, railAllowed = width - 300.dp >= 790.dp,
                            onMenu = {}, onSheet = { sheet = it },
                        )
                    }
                }
            } else {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(Modifier.width(320.dp), drawerContainerColor = Lx.colors.background) {
                            DrawerContent(
                                controller,
                                onSelect = { scope.launch { drawer.close() } },
                                onSheet = { sheet = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                ) {
                    val module = ModuleRouter.module
                    if (module != null) {
                        // 手机上模块页面全屏盖在翻译上，返回键回到翻译
                        ModulePage(module, controller, wide = false)
                    } else {
                        ConversationScreen(
                            controller, wide = false, railAllowed = false,
                            onMenu = { scope.launch { drawer.open() } }, onSheet = { sheet = it },
                        )
                    }
                }
            }
        }
        sheet?.let { current ->
            val close = { sheet = null }
            when (current) {
                RootSheet.Settings -> SettingsSheet(onDismiss = close)
                RootSheet.NewSession -> NewSessionSheet(controller, onDismiss = close, onNewScene = { sheet = RootSheet.NewScene })
                RootSheet.NewScene -> SceneEditorSheet(controller, sceneId = null, onDismiss = close)
                is RootSheet.EditScene -> SceneEditorSheet(controller, sceneId = current.id, onDismiss = close)
                RootSheet.Starred -> StarredSheet(controller, onDismiss = close)
            }
        }
    }
}
