package com.yishulabs.qtranslator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.HistoryStore
import com.yishulabs.qtranslator.conversation.SceneCover
import com.yishulabs.qtranslator.ui.modules.ConfirmDelete
import com.yishulabs.qtranslator.ui.modules.PendingDelete

private val Danger = Color(0xFFE5372B)

/** 新建会话：名称、所在场景、是否开启 AI 优化 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(controller: ConversationController, onDismiss: () -> Unit, onNewScene: () -> Unit) {
    val colors = Lx.colors
    val store = controller.store
    var name by remember { mutableStateOf("") }
    var sceneId by remember { mutableStateOf<String?>(null) }
    var aiEnabled by remember { mutableStateOf(AISettings.autoCalibrate) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("新建会话", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.ink, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, placeholder = { Text("例如：和老师约时间") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Footnote("不填也可以，会用第一句话当名称，之后随时能改。")
            Group("场景") {
                SceneChoice(null, "不放进场景", sceneId == null) { sceneId = null }
                store.scenes.forEach { scene ->
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.line)
                    SceneChoice(scene.cover, scene.name, sceneId == scene.id) { sceneId = scene.id }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.line)
                SettingRow("新建场景…", onClick = onNewScene)
            }
            Group(null) {
                SettingRow("这个会话开启 AI 优化") { Switch(aiEnabled, { aiEnabled = it }) }
            }
            Footnote("开启后每次翻译句子都会用 AI 优化译文，需要先在设置里填写 API Key。")
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                controller.select(store.createSession(name, sceneId, aiEnabled).id)
                onDismiss()
            }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                Text("创建", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun SceneChoice(cover: SceneCover?, name: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SceneCoverView(cover, 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, modifier = Modifier.weight(1f), color = Lx.colors.ink, fontSize = 16.sp)
        RadioButton(selected, onClick)
    }
}

/** 新建或编辑场景：名称和封面（图标 + 配色）；编辑时可以删除 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SceneEditorSheet(controller: ConversationController, sceneId: String?, onDismiss: () -> Unit) {
    val colors = Lx.colors
    val store = controller.store
    val existing = store.scene(sceneId)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var symbol by remember { mutableStateOf(existing?.cover?.symbol ?: SceneCover.symbols.first()) }
    var palette by remember { mutableStateOf(existing?.cover?.palette ?: 0) }
    var deleting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (existing == null) "新建场景" else "编辑场景", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.ink, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedTextField(name, { name = it }, label = { Text("场景名称") }, placeholder = { Text("例如：教室、户外交流、租房") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionHeader("封面")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SceneCoverView(SceneCover(symbol, palette), 72.dp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SceneCover.symbols.forEach { item ->
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (item == symbol) colors.accentSoft else colors.surface)
                            .clickable { symbol = item },
                        contentAlignment = Alignment.Center,
                    ) { Icon(sceneIcon(item), item, tint = colors.ink2) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(ScenePalette.count) { i ->
                    val (_, foreground) = ScenePalette.color(i)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .border(2.dp, if (i == palette) colors.ink else Color.Transparent, CircleShape)
                            .padding(5.dp).clip(CircleShape).background(foreground).clickable { palette = i },
                    )
                }
            }
            Button(onClick = {
                val cover = SceneCover(symbol, palette)
                if (existing != null) store.updateScene(existing.copy(name = name.trim(), cover = cover)) else store.createScene(name, cover)
                onDismiss()
            }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                Text(if (existing == null) "创建" else "保存", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            if (existing != null) {
                TextButton(onClick = { deleting = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("删除场景…", color = Danger)
                }
            }
        }
    }
    if (deleting && existing != null) {
        DeleteSceneDialog(controller, existing, onDismiss = {
            deleting = false
            if (store.scene(existing.id) == null) onDismiss()
        })
    }
}

/** 生词本：加了星标的词，点开在弹窗里看词条 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredSheet(controller: ConversationController, onDismiss: () -> Unit) {
    val colors = Lx.colors
    var open by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text("生词本", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.ink, modifier = Modifier.align(Alignment.CenterHorizontally))
            val starred = HistoryStore.starred
            if (starred.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Star, null, tint = colors.ink3, modifier = Modifier.size(40.dp))
                    Text("生词本是空的", fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text("在词典卡片上点星标，就能把词加进来。", color = colors.ink3, fontSize = 14.sp)
                }
            }
            LazyColumn {
                items(starred, key = { it.text }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { open = item.text }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.text, fontWeight = FontWeight.SemiBold, color = colors.ink, fontSize = 16.sp)
                            Text(item.summary, color = colors.ink3, fontSize = 13.sp, maxLines = 1)
                        }
                        IconButton(onClick = { HistoryStore.toggleStar(item.text) }) { Icon(Icons.Rounded.Close, "移除", tint = colors.ink3) }
                    }
                    HorizontalDivider(color = colors.line)
                }
            }
        }
    }
    open?.let { word -> WordSheet(word, null, translate = { controller.quickTranslate(it) }, onDismiss = { open = null }) }
}

/** 查看一张图片：旋转后重新识别这一张，或删除它和它的译文 */
@Composable
fun ImageViewer(controller: ConversationController, turnId: String, imageId: String, onDismiss: () -> Unit) {
    val store = controller.store
    val turn = store.turn(controller.currentId, turnId)
    val index = turn?.images?.indexOfFirst { it.id == imageId } ?: -1
    val item = turn?.images?.getOrNull(index)
    if (item == null) {
        // 这张图被删掉了（或整轮被删）：关掉
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val bitmap = remember(item.fileName, store.imageRevision) { store.image(item.fileName) }
    // 点“看原图 / 看译文”切换
    var showOriginal by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White) }
                Text("图 ${index + 1} / ${turn.images.size}", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("完成", color = Color(0xFF8CC0FF), fontWeight = FontWeight.SemiBold) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    TranslatedImage(bitmap, item.blocks ?: emptyList(), showTranslation = !showOriginal && item.done, description = "图 ${index + 1}")
                }
            }
            when {
                !item.done -> Row(Modifier.align(Alignment.CenterHorizontally).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("重新识别中…", color = Color.White, fontSize = 13.sp)
                }
                item.blocks.isNullOrEmpty() -> Text(
                    "没有识别到文字", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
                )
            }
            // 四个按钮等宽，图标在上、文字在下，窄屏也不换行
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(
                    if (showOriginal) "看译文" else "看原图", if (showOriginal) Icons.Rounded.Translate else Icons.Rounded.Image,
                ) { showOriginal = !showOriginal }
                ToolButton("重新识别", Icons.Rounded.Refresh, enabled = item.done) { controller.reprocessImage(turnId, imageId) }
                ToolButton("旋转", Icons.Rounded.Rotate90DegreesCw, enabled = item.done) { controller.rotateImage(turnId, imageId) }
                ToolButton("删除", Icons.Rounded.Delete, tint = Color(0xFFFF6B5E)) {
                    val isLast = turn.images.size <= 1
                    pendingDelete = PendingDelete(
                        "删除这张图片和它的译文？", if (isLast) "这是这一轮里的最后一张，整轮翻译会一起删除。" else null,
                    ) {
                        controller.deleteImage(turnId, imageId)
                        onDismiss()
                    }
                }
            }
        }
        ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null })
    }
}

@Composable
private fun RowScope.ToolButton(title: String, icon: ImageVector, tint: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
