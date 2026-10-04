package com.yishulabs.qtranslator.ui.modules

import androidx.compose.runtime.Composable
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.modules.AppModule

// 同声传译：首页、记录全文、进行中的传译页、收起后的小提示条。对应苹果版 ModuleViews.swift 的
// InterpretHome / InterpretRecordPage、InterpreterView.swift、InterpretSession.swift 和 Core/Interpreter.swift。

/** 开始一段传译（新开或接着 continuing 那条录）。离开页面也继续，已经在传译时只是回到传译页 */
fun beginInterpretation(continuing: String?, controller: ConversationController) {
    // TODO 同声传译
}

/** 同声传译首页：开始区 + 传译记录 */
@Composable
fun InterpretHome(top: @Composable () -> Unit, onOpen: (String) -> Unit, onStart: (String?) -> Unit) {
    ModuleScroll(top) { ModuleCaption("传译记录") }
}

/** 一条传译记录的全文 */
@Composable
fun InterpretRecordPage(id: String, onBack: () -> Unit, onContinue: () -> Unit) {
    ComingSoon(AppModule.INTERPRET.fullTitle, onBack)
}

/** 进行中的传译（全屏） */
@Composable
fun InterpreterScreen(onClose: () -> Unit) {
    ComingSoon(AppModule.INTERPRET.fullTitle, onClose)
}

/** 传译收起到后台时，其他模块页面顶上的小提示条；没在传译时不显示 */
@Composable
fun InterpretMiniBar(onOpen: () -> Unit) {}
