package com.yishulabs.qtranslator.conversation

import com.yishulabs.qtranslator.core.VoiceInput

// 会话里的语音输入：输入框没有内容时麦克风代替发送按钮。对应苹果版 ConversationController 的 startVoice / finishVoice / cancelVoice。

/** 开始语音输入（调用前要先拿到麦克风权限） */
fun ConversationController.startVoice() {
    VoiceInput.start(null)
}

/** 说完了：说完后直接翻译，或者先放进输入框（设置里可选） */
fun ConversationController.finishVoice() {
    val result = VoiceInput.stop()
    if (result.text.isBlank()) return
    sendText(result.text, result.audio, result.duration)
}

/** 取消语音输入 */
fun ConversationController.cancelVoice() {
    VoiceInput.cancel()
}
