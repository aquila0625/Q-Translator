package com.yishulabs.qtranslator.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.conversation.ChatSession
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.Direction
import com.yishulabs.qtranslator.conversation.startVoice
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.ui.modules.rememberMicPermission
import java.io.File

/**
 * 底部输入栏：拍照、相册多选、更多（文件、粘贴）、方向、AI 优化开关、麦克风或发送。
 * 选好的图片先放在输入框上方，发送时一起发；带着图片时输入框里写的是给 AI 的要求。
 * 平时最多占屏幕 30%，粘贴长文时放宽到约一半，超出部分在框里滚动。
 */
@Composable
fun Composer(controller: ConversationController, session: ChatSession, maxHeightDp: Int, onNeedAI: () -> Unit) {
    val colors = Lx.colors
    Surface(
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = colors.background,
        shadowElevation = 8.dp,
    ) {
        if (VoiceInput.isListening) {
            VoiceListeningPanel(controller)
        } else {
            Editor(controller, session, maxHeightDp, onNeedAI)
        }
    }
}

@Composable
private fun Editor(controller: ConversationController, session: ChatSession, maxHeightDp: Int, onNeedAI: () -> Unit) {
    val colors = Lx.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var more by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val withMic = rememberMicPermission()
    val isLong = controller.draft.length > 200
    val hasImages = controller.pendingImages.isNotEmpty()
    // 带着图片时，输入框里写的是给 AI 的要求；关着 AI 时不能输入
    val textLocked = hasImages && !session.aiEnabled
    val canSend = hasImages || controller.draft.isNotBlank()
    val placeholder = when {
        !hasImages -> "输入单词、句子或一段话"
        session.aiEnabled -> "告诉 AI 怎么翻译（可不填），例如：只翻译菜名"
        else -> "打开“AI 优化”后可以写要求"
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) loadBitmap(context, uri)?.let { controller.attachImages(listOf(it), "camera") }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        controller.attachImages(uris.mapNotNull { loadBitmap(context, it) }, "photos")
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.attachImages(uris.mapNotNull { loadBitmap(context, it) }, "files")
    }

    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        VoiceErrorBanner()
        if (hasImages) AttachmentTray(controller)
        if (isLong) {
            Text(
                "已输入 ${controller.draft.length} 个字符", fontSize = 12.sp, color = colors.ink3,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
        }
        val maxHeight = (maxHeightDp * if (isLong) 0.5f else 0.3f).coerceAtLeast(80f).dp
        Box(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = 10.dp, bottom = 4.dp)
                .alpha(if (textLocked) 0.5f else 1f),
        ) {
            if (controller.draft.isEmpty()) Text(placeholder, color = colors.ink3, fontSize = 17.sp)
            BasicTextField(
                value = controller.draft,
                onValueChange = { controller.draft = it },
                enabled = !textLocked,
                textStyle = TextStyle(fontSize = 17.sp, color = colors.ink, lineHeight = 24.sp),
                cursorBrush = SolidColor(colors.accent),
                // 查单词时不要被自动改成首字母大写
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 窄屏放不下时，“AI 优化”缩成“AI”
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasCamera) {
                    BarIcon(Icons.Rounded.PhotoCamera, "拍照翻译") {
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        cameraUri = uri
                        camera.launch(uri)
                    }
                }
                BarIcon(Icons.Rounded.PhotoLibrary, "从相册选图片（可多选）") {
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Box {
                    BarIcon(Icons.Rounded.MoreHoriz, "更多：从文件选择、粘贴", width = 36) { more = true }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("从文件选择") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, onClick = {
                            more = false
                            files.launch(arrayOf("image/*"))
                        })
                        DropdownMenuItem(text = { Text("粘贴剪贴板") }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) }, onClick = {
                            more = false
                            paste(context, controller)
                        })
                    }
                }
                Pill(
                    controller.direction.label, Icons.Rounded.SwapHoriz,
                    if (controller.direction != Direction.AUTO) colors.accent else colors.ink3,
                    if (controller.direction != Direction.AUTO) colors.accentSoft else colors.ink3.copy(alpha = 0.12f),
                    modifier = Modifier.semantics { contentDescription = "翻译方向：${controller.direction.label}，点按切换" },
                    height = 34.dp, onClick = { controller.cycleDirection() },
                )
                Spacer(Modifier.width(4.dp))
                Pill(
                    if (compact) "AI" else "AI 优化", Icons.Rounded.AutoAwesome,
                    if (session.aiEnabled) colors.ai else colors.ink3,
                    if (session.aiEnabled) colors.aiSoft else colors.ink3.copy(alpha = 0.12f),
                    modifier = Modifier.semantics { contentDescription = "AI 优化：" + if (session.aiEnabled) "开" else "关" },
                    height = 34.dp,
                    onClick = {
                        if (!session.aiEnabled && !AISettings.isConfigured) {
                            onNeedAI()
                            return@Pill
                        }
                        controller.setAI(!session.aiEnabled)
                    },
                )
                Spacer(Modifier.weight(1f))
                if (!canSend) {
                    // 输入框没有内容时，发送按钮换成麦克风
                    MicButton {
                        focus.clearFocus()
                        withMic { controller.startVoice() }
                    }
                } else {
                    Box(
                        Modifier.padding(end = 4.dp).size(40.dp).clip(CircleShape).background(colors.accent)
                            .clickable { controller.send() }
                            .semantics { contentDescription = "翻译" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, null, tint = colors.onAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, width: Int = 40, onClick: () -> Unit) {
    Box(
        Modifier.size(width.dp, 44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Lx.colors.accent, modifier = Modifier.size(22.dp))
    }
}

/** 剪贴板里是图片就放到输入框上方等着发送，是文字就放进输入框 */
private fun paste(context: Context, controller: ConversationController) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return
    val item = clip.getItemAt(0) ?: return
    val uri = item.uri
    if (uri != null && clip.description.hasMimeType("image/*")) {
        loadBitmap(context, uri)?.let { controller.attachImages(listOf(it), "paste") }
        return
    }
    item.coerceToText(context)?.toString()?.let { controller.draft += it }
}
