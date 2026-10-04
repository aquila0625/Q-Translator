package com.yishulabs.qtranslator.ui.modules

import androidx.compose.runtime.Composable
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.modules.AppModule
import com.yishulabs.qtranslator.modules.PracticeRecord

// 英语场景练习：首页、练习记录、进行中的练习页。对应苹果版 ModuleViews.swift 的 PracticeHome / PracticeRecordPage
// 和 PracticeView.swift。

/** onStart(seed)：seed 为 null 时先选场景 */
@Composable
fun PracticeHome(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: (PracticeRecord?) -> Unit) {
    ModuleScroll(top) { ModuleCaption("练习记录") }
}

@Composable
fun PracticeRecordPage(id: String, onBack: () -> Unit, onAgain: (PracticeRecord) -> Unit) {
    ComingSoon(AppModule.PRACTICE.fullTitle, onBack)
}

@Composable
fun PracticeScreen(controller: ConversationController, seed: PracticeRecord?, onClose: () -> Unit, onAgain: (PracticeRecord) -> Unit) {
    ComingSoon(AppModule.PRACTICE.fullTitle, onClose)
}
