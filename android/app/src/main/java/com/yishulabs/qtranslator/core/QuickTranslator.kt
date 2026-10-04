package com.yishulabs.qtranslator.core

/**
 * 面对面对话、同声传译这类要快的场景用的翻译：本机离线模型下载好了就用它（快、不限量），
 * 否则用在线翻译。翻译失败返回 null。
 */
object QuickTranslator {
    /** 本机模型是否可用；第一次查询后记住，下载模型后调用 refresh */
    @Volatile
    var onDevice: Boolean = false
        private set
    private var checked = false

    suspend fun refresh() {
        onDevice = OfflineTranslator.isReady()
        checked = true
    }

    suspend fun translate(text: String, fromChinese: Boolean): String? {
        if (text.isBlank()) return ""
        if (!checked) refresh()
        if (onDevice) runCatching { return OfflineTranslator.translate(text, fromChinese) }
        return runCatching { OnlineTranslator.translate(text, fromChinese) }.getOrNull()
    }
}
