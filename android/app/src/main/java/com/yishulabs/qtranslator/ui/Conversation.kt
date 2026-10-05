package com.yishulabs.qtranslator.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.conversation.Turn
import com.yishulabs.qtranslator.conversation.TurnKind
import com.yishulabs.qtranslator.conversation.kind
import com.yishulabs.qtranslator.core.SentenceResult
import com.yishulabs.qtranslator.core.WordEntry
import com.yishulabs.qtranslator.modules.AppModule
import com.yishulabs.qtranslator.modules.ModuleRouter
import com.yishulabs.qtranslator.ui.modules.ConfirmDelete
import com.yishulabs.qtranslator.ui.modules.InterpretMiniBar
import com.yishulabs.qtranslator.ui.modules.PendingDelete
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Danger = Color(0xFFE5372B)

/** 当前会话：顶栏、一轮轮的翻译、底部输入栏。宽屏时输入记录放在右边一栏。 */
@Composable
fun ConversationScreen(
    controller: ConversationController,
    wide: Boolean,
    railAllowed: Boolean,
    onMenu: () -> Unit,
    onSheet: (RootSheet) -> Unit,
) {
    val colors = Lx.colors
    val store = controller.store
    val session = store.session(controller.currentId)
    val turns = session?.turns ?: emptyList()
    val sessionId = controller.currentId
    // 顶部筛选：null 表示全部
    var filter by remember(sessionId) { mutableStateOf<TurnKind?>(null) }
    val rows = remember(turns, filter) { turnRows(turns, filter) }

    val listState = remember(sessionId) { rememberLazyListStateFor(turns.size) }
    val scope = rememberCoroutineScope()
    // 这次打开后新翻译的轮次默认展开；重新打开会话时长内容都收起
    var expanded by remember(sessionId) { mutableStateOf(setOf<String>()) }
    var lastCount by remember(sessionId) { mutableIntStateOf(turns.size) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var editingTurn by remember(sessionId) { mutableStateOf<String?>(null) }
    // 点选的那一轮显示操作按钮（最新一轮总是显示）；要删除、等确认的那一轮
    var selectedTurn by remember(sessionId) { mutableStateOf<String?>(null) }
    var deletingTurn by remember { mutableStateOf<PendingDelete?>(null) }
    var showOutline by remember { mutableStateOf(false) }
    var railOpen by rememberSaveable { mutableStateOf(true) }
    var word by remember { mutableStateOf<WordEntry?>(null) }
    var replyTo by remember { mutableStateOf<SentenceResult?>(null) }
    var imageRef by remember { mutableStateOf<Pair<String, String>?>(null) }
    var titleMenu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val bottomIndex = rows.size   // 最后有一个 1dp 的占位项，滚到它就是滚到最底下
    LaunchedEffect(turns.size) {
        // 只在发了新内容时滚到底；删掉一条时停在原处
        if (turns.size > lastCount) {
            expanded = expanded + turns.last().id
            // 发了新内容：回到全部，取消点选
            filter = null
            selectedTurn = null
            listState.animateScrollToItem(turns.size)
        }
        lastCount = turns.size
    }
    // 平时贴底（最新的在下面）；筛选时从顶部开始排
    var filterApplied by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(filter) {
        if (filter != null) {
            filterApplied = true
            listState.scrollToItem(0)
        } else if (filterApplied) {
            filterApplied = false
            listState.scrollToItem(bottomIndex)
        }
    }
    // 刚发出的这一轮出结果后变高，跟着滚到底
    val last = turns.lastOrNull()
    LaunchedEffect(last?.state, last?.sentence?.displayed, last?.word, last?.images?.count { it.done }) {
        if (last != null && System.currentTimeMillis() - last.createdAt < 60_000) listState.animateScrollToItem(bottomIndex)
    }
    val awayFromBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index < info.totalItemsCount - 1
        }
    }

    fun jumpTo(turnId: String) {
        // 跳过去时回到全部，下标按全部的轮次算
        filter = null
        val index = turns.indexOfFirst { it.id == turnId }
        if (index < 0) return
        scope.launch {
            delay(300)
            listState.animateScrollToItem(index, -120)
            delay(250)
            highlighted = turnId
            delay(2200)
            highlighted = null
        }
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(colors.wash, colors.background))))
            Column(Modifier.fillMaxSize().imePadding()) {
                // 顶栏
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!wide) BarButton(Icons.Rounded.Menu, "打开会话列表", onMenu)
                    Box(Modifier.weight(1f, fill = !wide)) {
                        Row(
                            Modifier.shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(colors.background)
                                .clickable { titleMenu = true }.height(44.dp).padding(horizontal = 14.dp)
                                .let { if (wide) it else it.fillMaxWidth() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            store.scene(session?.sceneId)?.let {
                                SceneCoverView(it.cover, 24.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                session?.title ?: "", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = titleMenu, onDismissRequest = { titleMenu = false }) {
                            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                                titleMenu = false
                                renaming = true
                            })
                            DropdownMenuItem(text = { Text("移到场景") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, onClick = {
                                titleMenu = false
                                moveMenu = true
                            })
                            DropdownMenuItem(text = { Text("删除会话", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) }, onClick = {
                                titleMenu = false
                                confirmDelete = true
                            })
                        }
                        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                            DropdownMenuItem(text = { Text("不放进场景") }, onClick = {
                                moveMenu = false
                                store.moveSession(sessionId, null)
                            })
                            store.scenes.forEach { scene ->
                                DropdownMenuItem(text = { Text(scene.name) }, leadingIcon = { SceneCoverView(scene.cover, 24.dp) }, onClick = {
                                    moveMenu = false
                                    store.moveSession(sessionId, scene.id)
                                })
                            }
                        }
                    }
                    if (wide) Spacer(Modifier.weight(1f))
                    BarButton(Icons.Rounded.History, "输入记录") {
                        if (wide && railAllowed) railOpen = !railOpen else showOutline = true
                    }
                    BarButton(Icons.Rounded.EditNote, "新建会话") { onSheet(RootSheet.NewSession) }
                }

                FilterBar(turns, filter) {
                    selectedTurn = null
                    filter = it
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(rows, key = { it.turn.id }) { row ->
                            val turn = row.turn
                            val isHighlighted = highlighted == turn.id
                            val picked = selectedTurn == turn.id
                            val scale by animateFloatAsState(if (isHighlighted) 1.02f else 1f, label = "highlight")
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                row.time?.let { time ->
                                    Text(
                                        time, fontSize = 12.sp, color = colors.ink3,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                            .background(colors.surface, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                                    )
                                }
                                // 往左滑露出删除，松手后确认
                                TurnSwipeToDelete(onDelete = { deletingTurn = deleteRequest(turn, controller) { if (selectedTurn == turn.id) selectedTurn = null } }) {
                                    Box(
                                        Modifier
                                            .graphicsLayer { scaleX = scale; scaleY = scale }
                                            // 跳过来的那一轮闪一下；点选的那一轮有一圈淡淡的边框
                                            .drawBehind {
                                                if (!isHighlighted && !picked) return@drawBehind
                                                val outset = 10.dp.toPx()
                                                val radius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                                                val topLeft = Offset(-outset, -outset)
                                                val area = Size(size.width + outset * 2, size.height + outset * 2)
                                                drawRoundRect(colors.accent.copy(alpha = if (isHighlighted) 0.14f else 0.04f), topLeft, area, radius)
                                                drawRoundRect(
                                                    colors.accent.copy(alpha = if (isHighlighted) 0.6f else 0.3f), topLeft, area, radius,
                                                    style = Stroke(width = (if (isHighlighted) 2.dp else 1.5.dp).toPx()),
                                                )
                                            },
                                    ) {
                                        TurnItem(
                                            turn = turn, controller = controller,
                                            expanded = turn.id in expanded,
                                            onToggleExpand = { expanded = if (turn.id in expanded) expanded - turn.id else expanded + turn.id },
                                            editing = editingTurn == turn.id,
                                            onEditingChange = { editingTurn = if (it) turn.id else null },
                                            onOpenWord = { word = it },
                                            onReply = { replyTo = it },
                                            onOpenImage = { imageRef = turn.id to it },
                                            onNeedAI = { AINeeded.request() },
                                            showsActions = turn.id == turns.lastOrNull()?.id || picked,
                                            onSelect = { selectedTurn = if (picked) null else turn.id },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
                    }
                    if (session != null && turns.isEmpty()) EmptyState(session.title)
                    // 往上翻看历史时，出现回到底部的箭头
                    androidx.compose.animation.AnimatedVisibility(
                        visible = awayFromBottom && turns.isNotEmpty(),
                        enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    ) {
                        Box(
                            Modifier.size(44.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(colors.background)
                                .clickable { scope.launch { listState.animateScrollToItem(bottomIndex) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, "回到最新的翻译", tint = colors.accent)
                        }
                    }
                }
                // 同声传译收起后，在输入框上方显示“正在传译”，点一下回去
                InterpretMiniBar(onOpen = {
                    ModuleRouter.open(AppModule.INTERPRET, from = "mini_bar")
                    ModuleRouter.launch = ModuleRouter.Launch.Interpret(null)
                })
                if (session != null) {
                    Composer(controller, session, maxHeightDp = screenHeight, onNeedAI = { AINeeded.request() })
                }
            }
        }
        if (wide && railAllowed && railOpen) {
            VerticalDivider(color = colors.line)
            OutlinePanel(turns, onSelect = { jumpTo(it) }, onClose = { railOpen = false }, modifier = Modifier.width(270.dp).fillMaxHeight())
        }
    }

    if (showOutline) {
        OutlineSheet(turns, onDismiss = { showOutline = false }) {
            showOutline = false
            jumpTo(it)
        }
    }
    word?.let { entry -> WordSheet(entry.word, entry, translate = { controller.quickTranslate(it) }, onDismiss = { word = null }) }
    replyTo?.let { sentence -> ReplySheet(sentence.source, sentence.displayed, onDismiss = { replyTo = null }) }
    imageRef?.let { (turnId, imageId) -> ImageViewer(controller, turnId, imageId, onDismiss = { imageRef = null }) }
    if (renaming) {
        RenameDialog(session?.title ?: "", onDismiss = { renaming = false }) { store.renameSession(sessionId, it) }
    }
    ConfirmDelete(deletingTurn, onDismiss = { deletingTurn = null })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个会话？") },
            text = { Text("会话里的翻译和图片都会被删除，不能恢复。") },
            confirmButton = {
                TextButton(onClick = { controller.deleteSession(sessionId); confirmDelete = false }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun rememberLazyListStateFor(count: Int) = androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = count)

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Lx.colors.background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Lx.colors.ink)
    }
}

/** 空会话：图标、标题、一句说明，加三条单行小提示，放在可见区域中间 */
@Composable
private fun EmptyState(title: String) {
    val colors = Lx.colors
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(68.dp).background(colors.accentSoft, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Translate, null, tint = colors.accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.ink)
        Text("在下面输入，开始翻译", fontSize = 15.sp, color = colors.ink3)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.background(colors.surface, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tip(Icons.Rounded.TextFields, "单词、句子或整段文字都可以")
            Tip(Icons.Rounded.PhotoCamera, "点左下角的相机拍照，或相册选图片")
            Tip(Icons.Rounded.Inventory2, "每次翻译都会保存在这个会话里")
        }
    }
}

@Composable
private fun Tip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Lx.colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 15.sp, color = Lx.colors.ink2)
    }
}

// 筛选、时间分隔、删除

/** 要显示的一轮，以及它上面要不要加时间分隔 */
private class TurnRow(val turn: Turn, val time: String?)

/** 和上一轮隔了 10 分钟以上时加时间分隔：同一天只写时间，换了一天带上日期 */
private fun turnRows(turns: List<Turn>, filter: TurnKind?): List<TurnRow> {
    // 界面都是中文，时间也固定用 24 小时制，不跟着手机的英文设置变成 “4:21 AM”
    val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    var previous: Long? = null
    return turns.filter { filter == null || it.kind == filter }.map { turn ->
        val before = previous
        previous = turn.createdAt
        if (before != null && turn.createdAt - before <= 600_000) return@map TurnRow(turn, null)
        val time = timeFormat.format(Date(turn.createdAt))
        val label = when {
            before != null && sameDay(before, turn.createdAt) -> time
            sameDay(System.currentTimeMillis(), turn.createdAt) -> "今天 $time"
            sameDay(System.currentTimeMillis() - 86_400_000, turn.createdAt) -> "昨天 $time"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(turn.createdAt)) + " " + time
        }
        TurnRow(turn, label)
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

/** 筛选栏：会话里有两种以上的内容时才出现 */
@Composable
private fun FilterBar(turns: List<Turn>, filter: TurnKind?, onChange: (TurnKind?) -> Unit) {
    val counts = TurnKind.entries.map { kind -> kind to turns.count { it.kind == kind } }.filter { it.second > 0 }
    if (counts.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip("全部 ${turns.size}", on = filter == null, kind = null) { onChange(null) }
        counts.forEach { (kind, count) ->
            FilterChip("${kind.title} $count", on = filter == kind, kind = kind) { onChange(if (filter == kind) null else kind) }
        }
    }
}

/** 筛选按钮的配色和会话里对应的卡片一致：单词浅蓝、句子浅绿、图片浅灰；选中时换成同色系的深色底、白字 */
@Composable
private fun FilterChip(title: String, on: Boolean, kind: TurnKind?, onClick: () -> Unit) {
    val colors = Lx.colors
    val ink = kind?.inkColor ?: colors.ink
    val card = kind?.cardColor ?: colors.ink3.copy(alpha = 0.12f)
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick)
            .semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) colors.background else if (kind == null) colors.ink2 else ink,
            modifier = Modifier.height(30.dp).background(if (on) ink else card, RoundedCornerShape(50))
                .border(1.dp, ink.copy(alpha = if (on || kind == null) 0f else 0.18f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp).wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/** 左滑露出红色删除，松手后由调用方确认。没在滑的时候不画红底，透明的地方（标签、时间）不会露出红色 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TurnSwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Danger).padding(end = 22.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Delete, null, tint = Color.White)
                        Text("删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { content() }
}

/** 删除确认的标题：说清楚删的是哪一条 */
private fun deleteRequest(turn: Turn, controller: ConversationController, after: () -> Unit): PendingDelete {
    val title = when (turn.kind) {
        TurnKind.WORD -> "删除单词“${turn.word?.word ?: turn.source}”？"
        TurnKind.IMAGE -> "删除这 ${turn.images.size} 张图片和译文？"
        TurnKind.SENTENCE -> "删除这句话和它的译文？"
    }
    return PendingDelete(title, "删除后不能恢复。") {
        controller.deleteTurn(turn.id)
        after()
    }
}

// 输入记录

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(turns: List<Turn>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Lx.colors.background) {
        var query by remember { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(0.85f)) {
            Text("输入记录", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Lx.colors.ink, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))
            SearchField(query, { query = it }, "搜索输入过的内容和译文")
            OutlineList(turns, query, onSelect)
        }
    }
}

@Composable
private fun OutlinePanel(turns: List<Turn>, onSelect: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    Column(modifier.background(Lx.colors.background).statusBarsPadding().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("输入记录", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Lx.colors.ink, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "收起输入记录", tint = Lx.colors.ink3) }
        }
        SearchField(query, { query = it }, "在会话里搜索")
        OutlineList(turns, query, onSelect)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val colors = Lx.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(50)).background(colors.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = colors.ink3, fontSize = 14.sp)
            BasicTextField(
                value, onChange, singleLine = true, cursorBrush = SolidColor(colors.accent),
                textStyle = TextStyle(fontSize = 14.sp, color = colors.ink), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OutlineList(turns: List<Turn>, query: String, onSelect: (String) -> Unit) {
    val colors = Lx.colors
    val q = query.trim().lowercase()
    val format = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    if (turns.isEmpty()) {
        Text("还没有内容", color = colors.ink3, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.padding(top = 8.dp)) {
        itemsIndexed(turns, key = { _, t -> t.id }) { index, turn ->
            if (q.isEmpty() || turn.searchableText.lowercase().contains(q)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(turn.id) }.padding(10.dp),
                ) {
                    Text("${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.ink3, modifier = Modifier.widthIn(min = 22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(turn.outlineText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, color = colors.ink)
                        val kind = when {
                            turn.isImage -> "图片"
                            turn.word != null -> "单词"
                            turn.sourceIsChinese -> "中文"
                            else -> "英文"
                        }
                        Text(
                            "$kind · ${format.format(Date(turn.createdAt))}" + if (turn.edited) " · 已编辑" else "",
                            fontSize = 12.sp, color = colors.ink3,
                        )
                    }
                }
            }
        }
    }
}
