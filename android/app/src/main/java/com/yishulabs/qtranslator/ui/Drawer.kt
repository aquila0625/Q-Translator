package com.yishulabs.qtranslator.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.conversation.ChatSession
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.SceneGroup

private val Danger = Color(0xFFE5372B)

/**
 * 会话列表：搜索、生词本和编辑，两层的场景和会话，底部是设置和新建。
 * 往左滑删除；长按会话可以重命名、移到别的场景；点“编辑”出现排序按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(
    controller: ConversationController,
    onSelect: () -> Unit,
    onSheet: (RootSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = controller.store
    val colors = Lx.colors
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf<ChatSession?>(null) }
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    var deletingScene by remember { mutableStateOf<SceneGroup?>(null) }

    Column(modifier.background(colors.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(8.dp))
        if (!editing) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(50)).background(colors.surface).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = colors.ink3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索会话和翻译", color = colors.ink3, fontSize = 15.sp)
                    BasicTextField(
                        query, { query = it }, singleLine = true, cursorBrush = SolidColor(colors.accent),
                        textStyle = TextStyle(fontSize = 15.sp, color = colors.ink), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (!editing && query.isBlank()) {
            Spacer(Modifier.height(10.dp))
            com.yishulabs.qtranslator.ui.modules.ModuleTiles(active = com.yishulabs.qtranslator.modules.ModuleRouter.module) { module ->
                com.yishulabs.qtranslator.modules.ModuleRouter.open(module)
                onSelect()
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                Text("用箭头排序；长按会话可以移到别的场景", fontSize = 13.sp, color = colors.ink3, modifier = Modifier.weight(1f).padding(start = 6.dp))
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSheet(RootSheet.Starred) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.StarBorder, null, tint = colors.ink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("生词本", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.ink)
                }
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = { editing = !editing }) {
                Text(if (editing) "完成" else "编辑", fontWeight = FontWeight.SemiBold)
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            val q = query.trim().lowercase()
            if (q.isNotEmpty()) {
                val matches = store.sessions.mapNotNull { session ->
                    when {
                        session.title.lowercase().contains(q) -> session to session.lastSnippet
                        else -> session.turns.lastOrNull { it.searchableText.lowercase().contains(q) }?.let { session to it.outlineText }
                    }
                }
                if (matches.isEmpty()) {
                    item { Text("没有找到“$query”", color = colors.ink3, modifier = Modifier.padding(top = 20.dp, start = 8.dp)) }
                }
                items(matches, key = { it.first.id }) { (session, snippet) ->
                    SessionRow(controller, session, snippet, editing, onSelect, { renaming = it }, { deleting = it })
                }
            } else {
                for (scene in store.scenes) {
                    val sessions = store.sessionsIn(scene.id)
                    val isCollapsed = scene.id in collapsed
                    stickyHeader(key = "h-" + scene.id) {
                        SceneHeader(
                            controller, scene, sessions.size, isCollapsed, editing,
                            onToggle = { collapsed = if (isCollapsed) collapsed - scene.id else collapsed + scene.id },
                            onNewSession = {
                                controller.newSession(sceneId = scene.id)
                                onSelect()
                            },
                            onEdit = { onSheet(RootSheet.EditScene(scene.id)) },
                            onDelete = { deletingScene = scene },
                        )
                    }
                    if (!isCollapsed) {
                        if (sessions.isEmpty()) {
                            item(key = "empty-" + scene.id) {
                                Text(
                                    "还没有会话，点右边的 + 新建，或长按会话移到这里", fontSize = 13.sp, color = colors.ink3,
                                    modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 12.dp),
                                )
                            }
                        }
                        items(sessions, key = { it.id }) { session ->
                            SessionRow(controller, session, null, editing, onSelect, { renaming = it }, { deleting = it })
                        }
                    }
                }
                // 不属于任何场景的会话：放在最下面，不显示“未分类”标题
                val unsorted = store.sessionsIn(null)
                if (unsorted.isNotEmpty() && store.scenes.isNotEmpty()) item { Spacer(Modifier.height(14.dp)) }
                items(unsorted, key = { it.id }) { session ->
                    SessionRow(controller, session, null, editing, onSelect, { renaming = it }, { deleting = it })
                }
                if (editing) {
                    item {
                        TextButton(onClick = { onSheet(RootSheet.NewScene) }, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("新建场景")
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSheet(RootSheet.Settings) }.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Settings, null, tint = colors.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("设置", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.ink)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(colors.accent).clickable { onSheet(RootSheet.NewSession) }
                    .height(40.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.EditNote, null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建", color = colors.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }

    renaming?.let { session ->
        RenameDialog(session.title, onDismiss = { renaming = null }) { store.renameSession(session.id, it) }
    }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个会话？") },
            text = { Text("“${session.title}”里的翻译和图片都会被删除，不能恢复。") },
            confirmButton = {
                TextButton(onClick = { controller.deleteSession(session.id); deleting = null }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    deletingScene?.let { scene ->
        DeleteSceneDialog(controller, scene, onDismiss = { deletingScene = null })
    }
}

/** 往左滑露出红色删除，松手后由调用方确认 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Danger).padding(end = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Delete, null, tint = Color.White)
                    Text("删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) { content() }
}

@Composable
private fun SceneHeader(
    controller: ConversationController,
    scene: SceneGroup,
    count: Int,
    collapsed: Boolean,
    editing: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Lx.colors
    Box(Modifier.background(colors.background).padding(top = 6.dp)) {
        SwipeToDelete(onDelete) {
            // 比会话行矮、颜色淡，不抢会话的注意力；右边依次是新建会话、编辑场景、展开收起
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface.copy(alpha = 0.7f))
                    .clickable(onClick = onToggle).defaultMinSize(minHeight = 44.dp).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SceneCoverView(scene.cover, 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(scene.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text("$count", fontSize = 12.sp, color = colors.ink3)
                Spacer(Modifier.weight(1f))
                if (editing) {
                    IconButton(onClick = { controller.store.moveScene(scene.id, up = true) }) { Icon(Icons.Rounded.KeyboardArrowUp, "上移", tint = colors.ink3) }
                    IconButton(onClick = { controller.store.moveScene(scene.id, up = false) }) { Icon(Icons.Rounded.KeyboardArrowDown, "下移", tint = colors.ink3) }
                    IconButton(onClick = onEdit) { Icon(Icons.Rounded.Tune, "编辑场景", tint = colors.ink3) }
                } else {
                    IconButton(onClick = onNewSession, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.EditNote, "在${scene.name}里新建会话", tint = colors.ink3, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Tune, "编辑场景${scene.name}", tint = colors.ink3, modifier = Modifier.size(18.dp))
                    }
                    Icon(
                        Icons.Rounded.ExpandMore, if (collapsed) "展开" else "收起", tint = colors.ink3,
                        modifier = Modifier.padding(end = 10.dp).size(20.dp).rotate(if (collapsed) -90f else 0f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    controller: ConversationController,
    session: ChatSession,
    snippet: String?,
    editing: Boolean,
    onSelect: () -> Unit,
    onRename: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
) {
    val colors = Lx.colors
    val store = controller.store
    var menu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    val selected = session.id == controller.currentId
    Box(Modifier.padding(vertical = 1.dp)) {
        SwipeToDelete({ onDelete(session) }) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.accentSoft else colors.background)
                    .combinedClickable(
                        onClick = {
                            controller.select(session.id)
                            onSelect()
                        },
                        onLongClick = { menu = true },
                    )
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(session.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(snippet ?: session.lastSnippet, fontSize = 12.sp, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (editing) {
                    IconButton(onClick = { store.nudgeSession(session.id, up = true) }) { Icon(Icons.Rounded.KeyboardArrowUp, "上移", tint = colors.ink2) }
                    IconButton(onClick = { store.nudgeSession(session.id, up = false) }) { Icon(Icons.Rounded.KeyboardArrowDown, "下移", tint = colors.ink2) }
                    IconButton(onClick = { onDelete(session) }) { Icon(Icons.Rounded.Delete, "删除", tint = Danger) }
                } else {
                    Text(
                        DateUtils.getRelativeTimeSpanString(session.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        fontSize = 11.sp, color = colors.ink3,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                menu = false
                onRename(session)
            })
            DropdownMenuItem(text = { Text("移到场景") }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) }, onClick = {
                menu = false
                moveMenu = true
            })
            DropdownMenuItem(text = { Text("删除", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) }, onClick = {
                menu = false
                onDelete(session)
            })
        }
        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
            DropdownMenuItem(text = { Text("不放进场景") }, onClick = {
                moveMenu = false
                store.moveSession(session.id, null)
            })
            store.scenes.forEach { scene ->
                DropdownMenuItem(text = { Text(scene.name) }, leadingIcon = { SceneCoverView(scene.cover, 24.dp) }, onClick = {
                    moveMenu = false
                    store.moveSession(session.id, scene.id)
                })
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("名称") }) },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }, enabled = text.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除场景的确认：可以勾选“连同里面的会话一起删除”，默认不勾选 */
@Composable
fun DeleteSceneDialog(controller: ConversationController, scene: SceneGroup, onDismiss: () -> Unit) {
    val count = controller.store.sessionsIn(scene.id).size
    var alsoSessions by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SceneCoverView(scene.cover, 44.dp) },
        title = { Text("删除场景“${scene.name}”？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (count == 0) {
                    Text("这个场景里没有会话。")
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { alsoSessions = !alsoSessions },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(alsoSessions, { alsoSessions = it }, colors = CheckboxDefaults.colors(checkedColor = Danger))
                        Text("同时删除这个场景里的 $count 个会话")
                    }
                    if (alsoSessions) {
                        Text(
                            "这 $count 个会话和里面所有的翻译、图片都会被删除，不能恢复。",
                            color = Danger, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(Danger.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
                        )
                    } else {
                        Text("不勾选时，里面的会话会移到列表最下面，不会被删除。", color = Lx.colors.ink3)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                controller.deleteScene(scene.id, alsoSessions)
                onDismiss()
            }) { Text(if (alsoSessions) "删除场景和会话" else "删除场景", color = Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
