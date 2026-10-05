package com.yishulabs.qtranslator.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 发现新版本：提示 → 下载（进度条）→ 安装。风格和 AINeededDialog 一致，由根界面显示一次。
 * mandatory=true 时不能取消，也没有“稍后/跳过此版本”。
 */
@Composable
fun UpdateDialog() {
    val r = UpdateChecker.release ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 去系统设置里允许安装后回到快译，自动继续
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) UpdateChecker.onResume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val phase = UpdateChecker.phase
    val mandatory = r.mandatory
    // 下载中和强制更新时不能点外面或返回键关掉
    val cancelable = !mandatory && phase != UpdateChecker.Phase.DOWNLOADING
    val properties = DialogProperties(dismissOnBackPress = cancelable, dismissOnClickOutside = cancelable)
    val onDismiss = { if (cancelable) UpdateChecker.dismiss() }

    when (phase) {
        UpdateChecker.Phase.PROMPT -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = properties,
            title = { Text("发现新版本 ${r.version}") },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.notes.ifEmpty { "本次更新包含体验改进和问题修复。" })
                    r.size?.let { Text("安装包大小：" + Formatter.formatShortFileSize(context, it)) }
                }
            },
            confirmButton = { TextButton(onClick = { UpdateChecker.accept() }) { Text("立即更新") } },
            dismissButton = if (mandatory) null else ({
                Row {
                    TextButton(onClick = { UpdateChecker.skip() }) { Text("跳过此版本") }
                    TextButton(onClick = { UpdateChecker.dismiss() }) { Text("稍后") }
                }
            }),
        )

        UpdateChecker.Phase.DOWNLOADING -> AlertDialog(
            onDismissRequest = {},
            properties = properties,
            title = { Text("正在下载 ${r.version}") },
            text = {
                val done = UpdateChecker.downloaded
                val total = UpdateChecker.total
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(Formatter.formatShortFileSize(context, done) + " / " + Formatter.formatShortFileSize(context, total))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("已下载 " + Formatter.formatShortFileSize(context, done))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { UpdateChecker.cancelDownload() }) { Text("取消") } },
        )

        UpdateChecker.Phase.READY -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = properties,
            title = { Text("新版本 ${r.version} 已下载") },
            text = { Text("点“安装”，在系统安装界面确认后就会更新到新版本。") },
            confirmButton = { TextButton(onClick = { UpdateChecker.install() }) { Text("安装") } },
            dismissButton = if (mandatory) null else ({ TextButton(onClick = { UpdateChecker.dismiss() }) { Text("稍后") } }),
        )

        UpdateChecker.Phase.PERMISSION -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = properties,
            title = { Text("需要允许安装应用") },
            text = { Text("更新要用系统安装器安装新版本，需要先允许“快译”安装应用。点“去允许”打开系统设置，打开“允许来自此来源的应用”后返回快译，会自动继续安装。") },
            confirmButton = { TextButton(onClick = { UpdateChecker.openInstallPermissionSettings() }) { Text("去允许") } },
            dismissButton = if (mandatory) null else ({ TextButton(onClick = { UpdateChecker.cancelPermission() }) { Text("取消") } }),
        )

        UpdateChecker.Phase.FAILED -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = properties,
            title = { Text("更新失败") },
            text = { Text(UpdateChecker.failure.ifEmpty { "下载失败，请检查网络后重试" }) },
            confirmButton = { TextButton(onClick = { UpdateChecker.startDownload() }) { Text("重试") } },
            dismissButton = if (mandatory) null else ({ TextButton(onClick = { UpdateChecker.dismiss() }) { Text("关闭") } }),
        )
    }
}
