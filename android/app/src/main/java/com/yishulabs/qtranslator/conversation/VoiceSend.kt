package com.yishulabs.qtranslator.conversation

import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.VoiceInput

// 会话里的语音输入：输入框没有内容时麦克风代替发送按钮。对应苹果版 ConversationController 的 startVoice / finishVoice / cancelVoice。

/** 开始语音输入（调用前要先拿到麦克风权限）：按翻译方向决定识别哪种语言，“自动”时用上次说的语言 */
fun ConversationController.startVoice() {
    VoiceInput.start(voiceLanguage)
}

/** 说完了：文字放进输入框（可以改），原声留着跟这次发送一起保存；设置了“说完自动翻译”就直接发出去 */
fun ConversationController.finishVoice() {
    val result = VoiceInput.stop()
    if (result.text.isBlank()) {
        result.audio?.let { store.deleteAudioFile(it) }
        return
    }
    Analytics.track(
        Analytics.Event.VOICE_INPUT,
        mapOf(
            "language" to VoiceInput.language.tag,
            "auto_send" to if (Prefs.voiceAutoSend) "yes" else "no",
            "seconds" to Analytics.bucket(result.duration.toInt(), listOf(5, 15, 60)),
        ),
    )
    // 输入框原来是空的：上一段没发出去的原声不要了
    if (draft.isBlank()) pendingAudio?.let { store.deleteAudioFile(it.name) }
    val text = if (draft.isBlank()) result.text else draft.trim() + " " + result.text
    draft = text
    result.audio?.let { pendingAudio = ConversationController.PendingAudio(it, result.duration) }
    if (Prefs.voiceAutoSend) send()
}

/** 取消语音输入 */
fun ConversationController.cancelVoice() {
    VoiceInput.cancel()
}
