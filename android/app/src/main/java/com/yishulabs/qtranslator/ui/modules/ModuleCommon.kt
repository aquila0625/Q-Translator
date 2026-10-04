package com.yishulabs.qtranslator.ui.modules

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishulabs.qtranslator.conversation.ConversationController
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.modules.AppModule
import com.yishulabs.qtranslator.modules.ModuleRouter
import com.yishulabs.qtranslator.modules.ModuleStore
import com.yishulabs.qtranslator.ui.Lx
import com.yishulabs.qtranslator.ui.RoundIconButton
import com.yishulabs.qtranslator.ui.SwipeToDelete

// 和苹果版 ModuleViews.swift 对应：模块入口、模块页面的外框、记录列表的共用部件

val AppModule.icon: ImageVector
    get() = when (this) {
        AppModule.INTERPRET -> Icons.Rounded.Subtitles
        AppModule.FACE -> Icons.Rounded.Groups
        AppModule.PRACTICE -> Icons.Rounded.TheaterComedy
    }

val AppModule.card: Color
    @Composable get() = when (this) {
        AppModule.INTERPRET -> Lx.colors.transcriptCard
        AppModule.FACE -> Lx.colors.dialogCard
        AppModule.PRACTICE -> Lx.colors.practiceCard
    }

val AppModule.ink: Color
    @Composable get() = when (this) {
        AppModule.INTERPRET -> Lx.colors.transcriptInk
        AppModule.FACE -> Lx.colors.dialogInk
        AppModule.PRACTICE -> Lx.colors.practiceInk
    }

/** 会话列表顶上的三个模块入口 */
@Composable
fun ModuleTiles(active: AppModule?, onOpen: (AppModule) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppModule.entries.forEach { module ->
            val subtitle = when (module) {
                AppModule.INTERPRET -> "${ModuleStore.interpretations.size} 条记录"
                AppModule.FACE -> "${ModuleStore.dialogs.size} 次对话"
                AppModule.PRACTICE -> "本周 ${ModuleStore.practicesThisWeek.size} 次"
            }
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).heightIn(min = 78.dp).clip(shape).background(module.card)
                    .then(if (active == module) Modifier.border(2.dp, module.ink, shape) else Modifier)
                    .clickable { onOpen(module) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Lx.colors.background.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(module.icon, null, tint = module.ink, modifier = Modifier.size(19.dp)) }
                Text(module.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = module.ink)
                Text(subtitle, fontSize = 11.sp, color = module.ink.copy(alpha = 0.8f))
            }
        }
    }
}

/** 点开的记录 */
sealed interface ModuleDetail {
    data class Interpret(val id: String) : ModuleDetail
    data class Dialog(val id: String) : ModuleDetail
    data class Practice(val id: String) : ModuleDetail
}

/**
 * 某个模块的页面：上面是开始区，下面是记录；点记录看全文。进行中的活动（传译、面对面、练习）全屏打开。
 * 手机上全屏盖在翻译上（有返回按钮）；平板宽屏显示在右边，点左边的会话回到翻译。
 */
@Composable
fun ModulePage(module: AppModule, controller: ConversationController, wide: Boolean) {
    var detail by remember(module) { mutableStateOf<ModuleDetail?>(null) }
    fun start(launch: ModuleRouter.Launch) {
        if (launch is ModuleRouter.Launch.Interpret) beginInterpretation(launch.continuing, controller)
        ModuleRouter.activity = launch
    }

    val launch = ModuleRouter.launch
    LaunchedEffect(launch) {
        if (launch != null) {
            ModuleRouter.launch = null
            detail = null
            start(launch)
        }
    }

    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null && !wide) { ModuleRouter.close() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Lx.colors.wash, 0.35f to Lx.colors.background))) {
        val top: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wide) {
                    Text(
                        module.fullTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Lx.colors.ink,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 20.dp),
                    )
                } else {
                    ModuleTopBar(module.fullTitle, onBack = { ModuleRouter.close() })
                }
                if (module != AppModule.INTERPRET) InterpretMiniBar(onOpen = { start(ModuleRouter.Launch.Interpret(null)) })
            }
        }
        when (val d = detail) {
            null -> when (module) {
                AppModule.INTERPRET -> InterpretHome(top, onOpen = { detail = ModuleDetail.Interpret(it) }, onStart = { start(ModuleRouter.Launch.Interpret(it)) })
                AppModule.FACE -> FaceHome(top, onOpen = { detail = ModuleDetail.Dialog(it) }, onStart = { start(ModuleRouter.Launch.Face) })
                AppModule.PRACTICE -> PracticeHome(top, onOpen = { detail = ModuleDetail.Practice(it) }, onStart = { start(ModuleRouter.Launch.Practice(it)) })
            }
            is ModuleDetail.Interpret -> InterpretRecordPage(d.id, onBack = { detail = null }, onContinue = { start(ModuleRouter.Launch.Interpret(d.id)) })
            is ModuleDetail.Dialog -> DialogRecordPage(d.id, onBack = { detail = null })
            is ModuleDetail.Practice -> PracticeRecordPage(d.id, onBack = { detail = null }, onAgain = { start(ModuleRouter.Launch.Practice(it)) })
        }
    }
}

/**
 * 进行中的活动（传译、面对面、练习）：盖住整个界面，连同左边的会话列表和状态栏下面。放在根界面的最上层。
 * 返回键由各个活动自己处理（结束并保存，或者收起传译）。
 */
@Composable
fun ModuleActivityHost(controller: ConversationController) {
    val current = ModuleRouter.activity ?: return
    val close = { ModuleRouter.activity = null }
    // 接住没被里面的按钮用掉的点击，不让它穿到下面的会话和抽屉上
    Box(
        Modifier.fillMaxSize().background(Lx.colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        when (current) {
            is ModuleRouter.Launch.Interpret -> InterpreterScreen(onClose = close)
            ModuleRouter.Launch.Face -> FaceToFaceScreen(controller, onClose = close)
            is ModuleRouter.Launch.Practice -> androidx.compose.runtime.key(current) {
                PracticeScreen(controller, seed = current.seed, onClose = close, onAgain = { ModuleRouter.activity = ModuleRouter.Launch.Practice(it) })
            }
        }
    }
}

/** 模块页面的顶栏：返回、居中的标题、右边可以放菜单 */
@Composable
fun ModuleTopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = { Spacer(Modifier.size(44.dp)) }) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
        Text(
            title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

/** 一条记录：点整行打开，右边一个按钮做常用操作（继续、查看、再练） */
@Composable
fun ModuleRow(module: AppModule, title: String, meta: String, pill: String, onOpen: () -> Unit, onPill: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Lx.colors.surface).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, fontSize = 12.sp, color = Lx.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(module.card).clickable(onClick = onPill)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) { Text(pill, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = module.ink) }
    }
}

/** 要删的一条记录和确认时显示的标题 */
data class PendingDelete(val title: String, val message: String? = null, val action: () -> Unit)

/** 删除前的确认对话框：pending 有值时弹出，点“删除”才执行 */
@Composable
fun ConfirmDelete(pending: PendingDelete?, onDismiss: () -> Unit, button: String = "删除") {
    pending ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pending.title) },
        text = pending.message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { pending.action(); onDismiss() }) { Text(button, color = Color(0xFFE5372B)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 记录列表：每条左滑出删除，点删除后确认 */
@Composable
fun RecordList(
    ids: List<String>,
    emptyText: String,
    deleteTitle: (String) -> String,
    onDelete: (String) -> Unit,
    row: @Composable (String) -> Unit,
) {
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    if (ids.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(22.dp)).background(Lx.colors.surface),
            contentAlignment = Alignment.Center,
        ) { Text(emptyText, fontSize = 13.sp, color = Lx.colors.ink3) }
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
            ids.forEachIndexed { index, id ->
                androidx.compose.runtime.key(id) {
                    SwipeToDelete(onDelete = { pending = PendingDelete(deleteTitle(id)) { onDelete(id) } }) { row(id) }
                }
                if (index < ids.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = Lx.colors.line)
            }
        }
    }
    ConfirmDelete(pending, onDismiss = { pending = null })
}

/** 开始区下面的小节标题 */
@Composable
fun ModuleCaption(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Lx.colors.ink3, modifier = Modifier.padding(start = 4.dp))
}

/** 模块首页的外框：顶栏 + 可以滚动的内容，宽屏时内容居中、不要太宽 */
@Composable
fun ModuleScroll(top: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        top()
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/**
 * 需要麦克风时调用：已经有权限就直接 onGranted，没有就先弹系统授权框，同意后再 onGranted。
 *   val withMic = rememberMicPermission()
 *   withMic { VoiceInput.start(...) }
 */
@Composable
fun rememberMicPermission(): (onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke() else VoiceInput.start() // 没权限时 start 会给出提示
    }
    return remember(context) {
        { onGranted ->
            if (VoiceInput.hasPermission(context)) onGranted() else {
                pendingAction = onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** 占位：还没做好的页面 */
@Composable
internal fun ComingSoon(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ModuleTopBar(title, onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在开发", color = Lx.colors.ink3) }
    }
}
