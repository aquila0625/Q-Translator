package com.yishulabs.qtranslator.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 点了要用 AI 的功能，但还没配置 API Key：弹出提示，点“去配置”直接进设置里的 AI 增强页。
 * 谁都可以调 [request]；弹窗由根界面显示一次。
 */
object AINeeded {
    var showing by mutableStateOf(false)
        private set

    fun request() { showing = true }

    fun dismiss() { showing = false }
}

@Composable
fun AINeededDialog(onConfigure: () -> Unit) {
    if (!AINeeded.showing) return
    AlertDialog(
        onDismissRequest = { AINeeded.dismiss() },
        title = { Text("需要先配置 AI") },
        text = { Text("这个功能要用 AI。先填写一个服务商的 API Key（Claude、ChatGPT、DeepSeek 都可以），填好后就能用了。") },
        confirmButton = { TextButton(onClick = { AINeeded.dismiss(); onConfigure() }) { Text("去配置") } },
        dismissButton = { TextButton(onClick = { AINeeded.dismiss() }) { Text("取消") } },
    )
}
