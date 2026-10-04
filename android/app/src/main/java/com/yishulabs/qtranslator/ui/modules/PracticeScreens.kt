package com.yishulabs.qtranslator.ui.modules

import androidx.compose.runtime.Composable
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.modules.PracticeRecord

// 英语场景练习的入口：首页、练习记录、进行中的练习页。对应苹果版 ModuleViews.swift 的 PracticeHome / PracticeRecordPage
// 和 PracticeView.swift。具体界面在 PracticeHome.kt、PracticeRecordView.kt、PracticeChat.kt、PracticeComposer.kt、PracticeSummary.kt

/** onStart(seed)：seed 为 null 时先选场景 */
@Composable
fun PracticeHome(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: (PracticeRecord?) -> Unit) =
    PracticeHomeView(top, onOpen, onStart)

@Composable
fun PracticeRecordPage(id: String, onBack: () -> Unit, onAgain: (PracticeRecord) -> Unit) =
    PracticeRecordView(id, onBack, onAgain)

@Composable
fun PracticeScreen(controller: ConversationController, seed: PracticeRecord?, onClose: () -> Unit, onAgain: (PracticeRecord) -> Unit) =
    PracticeActivity(seed, onClose)
