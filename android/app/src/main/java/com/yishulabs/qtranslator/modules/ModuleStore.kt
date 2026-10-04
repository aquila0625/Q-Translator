package com.yishulabs.qtranslator.modules

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yishulabs.qtranslator.core.Phrase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun newId() = UUID.randomUUID().toString()

/** 三个针对特定场景的模块：同声传译、面对面对话、场景练习。它们不属于某个翻译会话，各有自己的记录。 */
enum class AppModule(val title: String, val key: String) {
    INTERPRET("同声传译", "interpret"),
    FACE("面对面", "face"),
    PRACTICE("场景练习", "practice");

    val fullTitle: String get() = if (this == FACE) "面对面对话" else title
}

/** 面对面对话里的一句：谁说的、原话、译文 */
@Serializable
data class DialogLine(
    val id: String = newId(),
    /** true 是我说的，false 是对方说的 */
    val isMine: Boolean,
    val original: String,
    val translation: String,
    val originalIsChinese: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 场景练习里的一句：AI 扮演的角色说的，或者我说的（带 AI 给的更地道说法） */
@Serializable
data class PracticeLine(
    val id: String = newId(),
    val isMine: Boolean,
    val text: String,
    /** 中文意思（AI 说的话才有） */
    val chinese: String? = null,
    /** 我说的话：更地道的说法和原因；说得好就没有 */
    val better: String? = null,
    val reason: String? = null,
)

/** 一次场景练习 */
@Serializable
data class PracticeRecord(
    /** 场景，例如“租房：暖气坏了，和房东约时间来修” */
    val scenario: String,
    /** AI 扮演的角色，例如“房东” */
    val role: String,
    /** 难度：0 初级，1 中级，2 高级 */
    val level: Int,
    val lines: List<PracticeLine> = emptyList(),
    /** 练习里学到的新说法（英文 + 中文） */
    val phrases: List<Phrase> = emptyList(),
)

/** 同声传译记录里的一句 */
@Serializable
data class TranscriptLine(val id: String = newId(), val original: String, val translation: String)

/** 一条同声传译记录 */
@Serializable
data class InterpretRecord(
    val id: String = newId(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceIsChinese: Boolean,
    val lines: List<TranscriptLine> = emptyList(),
    /** 收音时长（秒），继续录时累加 */
    val duration: Double = 0.0,
    /** AI 总结的要点（有 Key 时可以生成） */
    val summary: String? = null,
) {
    companion object {
        fun defaultTitle(time: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time)) + " 传译"
    }
}

/** 一次面对面对话 */
@Serializable
data class DialogRecord(val id: String = newId(), val createdAt: Long = System.currentTimeMillis(), val lines: List<DialogLine> = emptyList())

/** 一次场景练习 */
@Serializable
data class PracticeEntry(val id: String = newId(), val createdAt: Long = System.currentTimeMillis(), val record: PracticeRecord)

/** 三个模块的记录，存在本机的一个 JSON 文件里。每个列表最新的在前面。 */
object ModuleStore {
    var interpretations by mutableStateOf(listOf<InterpretRecord>())
        private set
    var dialogs by mutableStateOf(listOf<DialogRecord>())
        private set
    var practices by mutableStateOf(listOf<PracticeEntry>())
        private set

    @Serializable
    private data class Snapshot(
        val interpretations: List<InterpretRecord> = emptyList(),
        val dialogs: List<DialogRecord> = emptyList(),
        val practices: List<PracticeEntry> = emptyList(),
    )

    private lateinit var file: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null

    fun init(context: Context) {
        file = File(context.filesDir, "modules.json")
        runCatching { json.decodeFromString<Snapshot>(file.readText()) }.getOrNull()?.let {
            interpretations = it.interpretations
            dialogs = it.dialogs
            practices = it.practices
        }
    }

    /** 合并短时间内的多次修改，只写一次文件 */
    private fun save() {
        saveJob?.cancel()
        val snapshot = Snapshot(interpretations, dialogs, practices)
        saveJob = scope.launch {
            delay(300)
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(snapshot))
                    tmp.renameTo(file)
                }
            }
        }
    }

    // 同声传译

    fun interpretation(id: String) = interpretations.firstOrNull { it.id == id }

    /**
     * 结束一段传译：新的一条放在最前面；继续录的接在原来那条后面，时长累加。没说话就不存。返回记录的 ID
     */
    fun saveInterpretation(lines: List<TranscriptLine>, duration: Double, sourceIsChinese: Boolean, into: String?): String? {
        val existing = into?.let { interpretation(it) }
        if (existing != null) {
            val updated = existing.copy(
                lines = existing.lines + lines,
                duration = existing.duration + duration,
                updatedAt = if (lines.isNotEmpty()) System.currentTimeMillis() else existing.updatedAt,
            )
            interpretations = listOf(updated) + interpretations.filter { it.id != existing.id }
            save()
            return existing.id
        }
        if (lines.isEmpty()) return null
        val now = System.currentTimeMillis()
        val record = InterpretRecord(
            title = InterpretRecord.defaultTitle(now), createdAt = now, updatedAt = now,
            sourceIsChinese = sourceIsChinese, lines = lines, duration = duration,
        )
        interpretations = listOf(record) + interpretations
        save()
        return record.id
    }

    fun updateInterpretation(id: String, change: (InterpretRecord) -> InterpretRecord) {
        interpretations = interpretations.map { if (it.id == id) change(it) else it }
        save()
    }

    fun deleteInterpretation(id: String) {
        interpretations = interpretations.filter { it.id != id }
        save()
    }

    // 面对面对话

    fun dialog(id: String) = dialogs.firstOrNull { it.id == id }

    fun addDialog(lines: List<DialogLine>) {
        if (lines.isEmpty()) return
        dialogs = listOf(DialogRecord(lines = lines)) + dialogs
        save()
    }

    fun deleteDialog(id: String) {
        dialogs = dialogs.filter { it.id != id }
        save()
    }

    // 场景练习

    fun practice(id: String) = practices.firstOrNull { it.id == id }

    /** 练习过程中每一轮都存一下：第一次新建，之后更新同一条。我还没说过话时不存。返回记录的 ID */
    fun savePractice(record: PracticeRecord, into: String?): String? {
        if (record.lines.none { it.isMine }) return into
        if (into != null && practices.any { it.id == into }) {
            practices = practices.map { if (it.id == into) it.copy(record = record) else it }
            save()
            return into
        }
        val entry = PracticeEntry(record = record)
        practices = listOf(entry) + practices
        save()
        return entry.id
    }

    fun deletePractice(id: String) {
        practices = practices.filter { it.id != id }
        save()
    }

    /** 最近 7 天的练习 */
    val practicesThisWeek: List<PracticeEntry>
        get() {
            val start = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            return practices.filter { it.createdAt >= start }
        }
}

/** 正在看哪个模块，以及进入模块后马上开始的那件事 */
object ModuleRouter {
    sealed interface Launch {
        /** 同声传译：continuing 是要接着录的那条记录，null 表示新开一条 */
        data class Interpret(val continuing: String?) : Launch
        data object Face : Launch
        /** 场景练习：seed 是直接练的场景（首页点了某个场景，或者“再练一次”），null 时先选场景 */
        data class Practice(val seed: PracticeRecord?) : Launch
    }

    /** 正在看的模块（手机上全屏盖在翻译上；平板宽屏显示在右边） */
    var module by mutableStateOf<AppModule?>(null)

    /** 进入模块后马上开始的那件事（例如桌面快捷方式“同声传译”直接开录） */
    var launch by mutableStateOf<Launch?>(null)

    /** 正在进行、全屏盖住整个界面的活动：传译、面对面对话或练习（ModuleActivityHost 显示它） */
    var activity by mutableStateOf<Launch?>(null)

    /** 桌面快捷方式“新建翻译” */
    var newSessionRequested by mutableStateOf(false)

    fun open(module: AppModule, start: Boolean = false, from: String = "drawer") {
        if (this.module != module) {
            com.yishulabs.qtranslator.analytics.Analytics.track(
                com.yishulabs.qtranslator.analytics.Analytics.Event.MODULE_OPEN, mapOf("module" to module.key, "from" to from),
            )
        }
        this.module = module
        if (!start) return
        launch = when (module) {
            AppModule.INTERPRET -> Launch.Interpret(null)
            AppModule.FACE -> Launch.Face
            AppModule.PRACTICE -> null
        }
    }

    fun close() {
        module = null
    }
}
