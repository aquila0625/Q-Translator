package com.yishulabs.qtranslator.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yishulabs.qtranslator.analytics.Analytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 会话和场景的本机存储：一个 JSON 文件，图片另存为 JPEG 文件。 */
class ConversationStore(context: Context, private val scope: CoroutineScope) {
    var scenes by mutableStateOf(listOf<SceneGroup>())
        private set

    /** 列表顺序就是界面里的顺序（同一场景内按出现先后） */
    var sessions by mutableStateOf(listOf<ChatSession>())
        private set

    @Serializable
    private data class Snapshot(val scenes: List<SceneGroup>, val sessions: List<ChatSession>)

    private val file = File(context.filesDir, "conversations.json")
    val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
    /** 语音输入录下的原声（VoiceInput 存在这里） */
    private val audioDir = File(context.filesDir, "audio")

    /** 图片文件被替换（旋转）一次加一：界面按它重新读图，文件名不变也能刷新 */
    var imageRevision by mutableIntStateOf(0)
        private set
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var saveJob: Job? = null
    private val imageCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        runCatching { json.decodeFromString<Snapshot>(file.readText()) }.getOrNull()?.let { snapshot ->
            scenes = snapshot.scenes
            // 上次退出时还在翻译的轮次，重新打开后标成失败，可以点重试
            sessions = snapshot.sessions.map { session ->
                session.copy(turns = session.turns.map { turn ->
                    if (turn.state == TurnState.WORKING) {
                        turn.copy(state = TurnState.FAILED, errorMessage = "翻译被中断了", isOptimizing = false)
                    } else {
                        turn.copy(isOptimizing = false)
                    }
                })
            }
        }
    }

    // 保存

    /** 合并短时间内的多次修改，只写一次文件 */
    private fun save() {
        saveJob?.cancel()
        val snapshot = Snapshot(scenes, sessions)
        saveJob = scope.launch {
            delay(300)
            withContext(Dispatchers.IO) {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json.encodeToString(snapshot))
                temp.renameTo(file)
            }
        }
    }

    // 会话

    fun session(id: String?): ChatSession? = sessions.firstOrNull { it.id == id }

    fun sessionsIn(sceneId: String?): List<ChatSession> = sessions.filter { it.sceneId == sceneId }

    fun createSession(title: String?, sceneId: String?, aiEnabled: Boolean): ChatSession {
        val name = title?.trim().orEmpty()
        val session = ChatSession(
            title = name.ifEmpty { ChatSession.DEFAULT_TITLE }, autoTitled = name.isEmpty(),
            sceneId = sceneId, aiEnabled = aiEnabled,
        )
        sessions = listOf(session) + sessions
        save()
        return session
    }

    fun updateSession(id: String, change: (ChatSession) -> ChatSession) {
        sessions = sessions.map { if (it.id == id) change(it) else it }
        save()
    }

    fun renameSession(id: String, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        updateSession(id) { it.copy(title = name, autoTitled = false) }
    }

    /** 移到某个场景（null 表示不放进场景），排在那个场景的最上面 */
    fun moveSession(id: String, sceneId: String?) {
        val session = session(id) ?: return
        val rest = sessions.filter { it.id != id }
        val moved = session.copy(sceneId = sceneId)
        val first = rest.indexOfFirst { it.sceneId == sceneId }
        sessions = if (first >= 0) rest.take(first) + moved + rest.drop(first) else rest + moved
        save()
    }

    /** 在同一个场景里上移或下移一位 */
    fun nudgeSession(id: String, up: Boolean) {
        val session = session(id) ?: return
        val group = sessionsIn(session.sceneId)
        val index = group.indexOfFirst { it.id == id }
        val other = group.getOrNull(if (up) index - 1 else index + 1) ?: return
        sessions = sessions.map {
            when (it.id) {
                id -> other
                other.id -> session
                else -> it
            }
        }
        save()
    }

    fun deleteSession(id: String) {
        session(id)?.turns?.flatMap { it.images }?.forEach { deleteImageFile(it.fileName) }
        session(id)?.turns?.mapNotNull { it.audioFile }?.forEach { deleteAudioFile(it) }
        sessions = sessions.filter { it.id != id }
        save()
    }

    // 场景

    fun scene(id: String?): SceneGroup? = id?.let { sceneId -> scenes.firstOrNull { it.id == sceneId } }

    fun createScene(name: String, cover: SceneCover): SceneGroup {
        val scene = SceneGroup(name = name.trim().ifEmpty { "新场景" }, cover = cover)
        scenes = scenes + scene
        Analytics.track(Analytics.Event.SCENE_NEW)
        save()
        return scene
    }

    fun updateScene(scene: SceneGroup) {
        scenes = scenes.map { if (it.id == scene.id) scene else it }
        save()
    }

    fun moveScene(id: String, up: Boolean) {
        val index = scenes.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in scenes.indices) return
        scenes = scenes.toMutableList().apply { add(target, removeAt(index)) }
        save()
    }

    /** 删除场景。默认里面的会话移到列表最下面；deleteSessions 为 true 时连同会话一起删除 */
    fun deleteScene(id: String, deleteSessions: Boolean = false) {
        sessions = if (deleteSessions) {
            sessions.filter { it.sceneId == id }.flatMap { it.turns }.flatMap { it.images }.forEach { deleteImageFile(it.fileName) }
            sessions.filter { it.sceneId != id }
        } else {
            sessions.map { if (it.sceneId == id) it.copy(sceneId = null) else it }
        }
        scenes = scenes.filter { it.id != id }
        save()
    }

    // 轮次

    fun turn(sessionId: String, turnId: String): Turn? = session(sessionId)?.turns?.firstOrNull { it.id == turnId }

    fun appendTurn(turn: Turn, sessionId: String) {
        updateSession(sessionId) { it.copy(turns = it.turns + turn, updatedAt = System.currentTimeMillis()) }
    }

    fun updateTurn(sessionId: String, turnId: String, change: (Turn) -> Turn) {
        updateSession(sessionId) { session ->
            session.copy(turns = session.turns.map { if (it.id == turnId) change(it) else it })
        }
    }

    fun deleteTurn(sessionId: String, turnId: String) {
        turn(sessionId, turnId)?.images?.forEach { deleteImageFile(it.fileName) }
        turn(sessionId, turnId)?.audioFile?.let { deleteAudioFile(it) }
        updateSession(sessionId) { session -> session.copy(turns = session.turns.filter { it.id != turnId }) }
    }

    // 图片

    /** 存成 JPEG，长边最多 2400 像素，够识别文字又不占太多空间 */
    fun saveImage(bitmap: Bitmap): String? = runCatching {
        val name = UUID.randomUUID().toString() + ".jpg"
        File(imagesDir, name).outputStream().use { scaled(bitmap, 2400).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }.getOrNull()

    fun replaceImage(name: String, bitmap: Bitmap) {
        runCatching { File(imagesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        forgetCached(name)
        imageRevision++
    }

    fun image(name: String): Bitmap? {
        imageCache.get(name)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(File(imagesDir, name).path) ?: return null
        imageCache.put(name, bitmap)
        return bitmap
    }

    /** 缩略图：按需要的边长缩小解码，省内存 */
    fun thumbnail(name: String, side: Int = 360): Bitmap? {
        val key = "thumb$side:$name"
        imageCache.get(key)?.let { return it }
        val path = File(imagesDir, name).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        imageCache.put(key, bitmap)
        return bitmap
    }

    fun deleteImageFile(name: String) {
        forgetCached(name)
        File(imagesDir, name).delete()
    }

    fun deleteAudioFile(name: String) {
        File(audioDir, name).delete()
    }

    /** 原图和各种尺寸的缩略图都从缓存里去掉 */
    private fun forgetCached(name: String) {
        imageCache.snapshot().keys.filter { it == name || it.endsWith(":$name") }.forEach { imageCache.remove(it) }
    }

    companion object {
        fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
            val side = maxOf(bitmap.width, bitmap.height)
            if (side <= maxSide) return bitmap
            val ratio = maxSide.toFloat() / side
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        }
    }
}
