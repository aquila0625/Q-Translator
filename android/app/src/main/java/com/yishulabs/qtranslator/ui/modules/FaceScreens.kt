package com.yishulabs.qtranslator.ui.modules

import androidx.compose.runtime.Composable
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.modules.AppModule

// 面对面对话：首页、对话记录、进行中的对话页。对应苹果版 ModuleViews.swift 的 FaceHome / DialogRecordPage
// 和 FaceToFaceView.swift。

@Composable
fun FaceHome(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: () -> Unit) {
    ModuleScroll(top) { ModuleCaption("对话记录") }
}

@Composable
fun DialogRecordPage(id: String, onBack: () -> Unit) {
    ComingSoon(AppModule.FACE.fullTitle, onBack)
}

@Composable
fun FaceToFaceScreen(controller: ConversationController, onClose: () -> Unit) {
    ComingSoon(AppModule.FACE.fullTitle, onClose)
}
