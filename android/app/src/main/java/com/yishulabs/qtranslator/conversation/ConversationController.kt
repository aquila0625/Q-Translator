package com.yishulabs.qtranslator.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.AITasks
import com.yishulabs.qtranslator.core.ImageText
import com.yishulabs.qtranslator.core.OfflineTranslator
import com.yishulabs.qtranslator.core.OnlineTranslator
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.SentenceResult
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.core.Suggestion
import com.yishulabs.qtranslator.core.Youdao
import com.yishulabs.qtranslator.core.containsChinese
import com.yishulabs.qtranslator.core.isMostlyChinese
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Direction(val label: String) { AUTO("自动"), EN_TO_ZH("英→中"), ZH_TO_EN("中→英") }

/** 会话页的大脑：发送、翻译、编辑原文、AI 优化、图片处理。 */
class ConversationController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = ConversationStore(context, scope)

    var currentId by mutableStateOf("")
        private set
    var draft by mutableStateOf("")
    var direction by mutableStateOf(Direction.AUTO)
        private set
    /** 离线翻译模型可以下载但还没下载 */
    var offlineDownloadable by mutableStateOf(false)
        private set
    var downloadingModel by mutableStateOf(false)
        private set

    init {
        currentId = store.sessions.maxByOrNull { it.updatedAt }?.id
            ?: store.createSession(null, null, AISettings.autoCalibrate).id
    }

    val current: ChatSession? get() = store.session(currentId)

    // 会话

    fun select(id: String) {
        currentId = id
        direction = Direction.AUTO
    }

    fun newSession(title: String? = null, sceneId: String? = null, aiEnabled: Boolean? = null) {
        // 当前会话还是空的、也没起名，就直接复用它（移到要的场景里）
        val now = current
        if (title == null && now != null && now.turns.isEmpty() && now.autoTitled) {
            store.moveSession(now.id, sceneId)
            return
        }
        select(store.createSession(title, sceneId, aiEnabled ?: AISettings.autoCalibrate).id)
    }

    private fun selectLatestOrCreate() {
        currentId = store.sessions.maxByOrNull { it.updatedAt }?.id
            ?: store.createSession(null, null, AISettings.autoCalibrate).id
    }

    fun deleteSession(id: String) {
        store.deleteSession(id)
        if (currentId == id) selectLatestOrCreate()
    }

    fun deleteScene(id: String, deleteSessions: Boolean) {
        val removesCurrent = deleteSessions && current?.sceneId == id
        store.deleteScene(id, deleteSessions)
        if (removesCurrent) selectLatestOrCreate()
    }

    fun setAI(on: Boolean) = store.updateSession(currentId) { it.copy(aiEnabled = on) }

    /** 方向按钮：自动 → 英译中 → 中译英 → 自动 */
    fun cycleDirection() {
        direction = Direction.entries[(direction.ordinal + 1) % Direction.entries.size]
    }

    private fun sourceIsChinese(text: String) = when (direction) {
        Direction.AUTO -> text.isMostlyChinese
        Direction.EN_TO_ZH -> false
        Direction.ZH_TO_EN -> true
    }

    // 发送

    /** 发送输入框里的内容 */
    fun send() {
        val text = draft
        if (text.isBlank()) return
        draft = ""
        sendText(text)
    }

    /** 直接翻译一段文字（分享进来的、在词条里查的） */
    /** audioFile / audioDuration：语音输入时录下的原声（在 filesDir/audio 下），之后可以回放 */
    fun sendText(text: String, audioFile: String? = null, audioDuration: Double? = null) {
        val source = text.trim()
        if (source.isEmpty()) return
        val turn = Turn(
            source = source, sourceIsChinese = sourceIsChinese(source), manualDirection = direction != Direction.AUTO,
            audioFile = audioFile, audioDuration = audioDuration,
        )
        val sessionId = currentId
        store.appendTurn(turn, sessionId)
        scope.launch { process(sessionId, turn.id) }
    }

    /** 一次发送多张图片，作为同一轮 */
    fun sendImages(bitmaps: List<Bitmap>) {
        val files = bitmaps.mapNotNull { store.saveImage(it) }
        if (files.isEmpty()) return
        val turn = Turn(
            source = "", images = files.map { TurnImage(fileName = it) },
            sourceIsChinese = direction == Direction.ZH_TO_EN, manualDirection = direction != Direction.AUTO,
        )
        val sessionId = currentId
        store.appendTurn(turn, sessionId)
        scope.launch { process(sessionId, turn.id) }
    }

    /** 把译文放到原文的位置再反向翻译一次，作为新的一轮 */
    fun swap(turn: Turn) {
        val translation = turn.sentence?.displayed ?: return
        val new = Turn(source = translation, sourceIsChinese = !turn.sourceIsChinese, manualDirection = true)
        val sessionId = currentId
        store.appendTurn(new, sessionId)
        scope.launch { process(sessionId, new.id) }
    }

    fun retry(turnId: String) {
        val sessionId = currentId
        store.updateTurn(sessionId, turnId) { it.copy(state = TurnState.WORKING, errorMessage = null) }
        scope.launch { process(sessionId, turnId) }
    }

    /** 改了原文：只重新翻译这一轮 */
    fun editSource(turnId: String, text: String) {
        val source = text.trim()
        val old = store.turn(currentId, turnId) ?: return
        if (source.isEmpty() || old.source == source) return
        val sessionId = currentId
        store.updateTurn(sessionId, turnId) {
            it.copy(
                source = source, edited = true,
                sourceIsChinese = if (it.manualDirection) it.sourceIsChinese else source.isMostlyChinese,
                state = TurnState.WORKING, word = null, sentence = null, errorMessage = null, aiError = null,
            )
        }
        scope.launch { process(sessionId, turnId) }
    }

    fun deleteTurn(turnId: String) = store.deleteTurn(currentId, turnId)

    /** 删掉一张图片，它的译文一起去掉；最后一张也删了就删除整轮 */
    fun deleteImage(turnId: String, imageId: String) {
        val turn = store.turn(currentId, turnId) ?: return
        val image = turn.images.firstOrNull { it.id == imageId } ?: return
        if (turn.images.size == 1) {
            deleteTurn(turnId)
            return
        }
        store.deleteImageFile(image.fileName)
        store.updateTurn(currentId, turnId) { t ->
            val images = t.images.filter { it.id != imageId }
            t.copy(images = images, source = images.joinToString("\n") { it.recognized })
        }
    }

    /** 把图片顺时针转 90°，然后只重新识别和翻译这一张 */
    fun rotateImage(turnId: String, imageId: String) {
        val turn = store.turn(currentId, turnId) ?: return
        val item = turn.images.firstOrNull { it.id == imageId } ?: return
        val bitmap = store.image(item.fileName) ?: return
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
        store.replaceImage(item.fileName, rotated)
        val sessionId = currentId
        store.updateTurn(sessionId, turnId) { t ->
            t.copy(
                images = t.images.map { if (it.id == imageId) it.copy(recognized = "", translation = "", done = false) else it },
                state = TurnState.WORKING,
            )
        }
        scope.launch { process(sessionId, turnId) }
    }

    // 翻译

    private suspend fun process(sessionId: String, turnId: String) {
        val turn = store.turn(sessionId, turnId) ?: return
        if (turn.isImage) processImages(sessionId, turn) else processText(sessionId, turn)
    }

    private suspend fun processText(sessionId: String, turn: Turn) {
        val text = turn.source
        var suggestions = emptyList<Suggestion>()

        if (isWordLike(text)) {
            val response = runCatching { Youdao.lookup(text) }.getOrNull()
            val entry = response?.entry
            if (entry != null) {
                store.updateTurn(sessionId, turn.id) { it.copy(word = entry, state = TurnState.DONE) }
                HistoryStore.add(entry.word, entry.summary)
                autoTitle(sessionId, text)
                if (Prefs.autoSpeak) Speaker.play(Speech.text(entry.word, entry.isChinese))
                return
            }
            suggestions = response?.suggestions ?: emptyList()
        }

        try {
            val (translation, engine) = translateSentence(text, turn.sourceIsChinese)
            store.updateTurn(sessionId, turn.id) {
                it.copy(
                    sentence = SentenceResult(text, translation, turn.sourceIsChinese, engine, suggestions),
                    state = TurnState.DONE,
                )
            }
            autoTitle(sessionId, text)
            // 单词和短语不自动用 AI：它们查词典就够了，AI 优化只针对整句话
            if (store.session(sessionId)?.aiEnabled == true && AISettings.isConfigured && !isWordLike(text)) optimize(sessionId, turn.id)
        } catch (e: Exception) {
            store.updateTurn(sessionId, turn.id) { it.copy(state = TurnState.FAILED, errorMessage = "翻译失败，请检查网络后重试。") }
        }
    }

    private suspend fun processImages(sessionId: String, turn: Turn) {
        for (item in turn.images.filter { !it.done }) {
            val bitmap = withContext(Dispatchers.IO) { store.image(item.fileName) } ?: continue
            val recognized = runCatching { ImageText.recognize(bitmap) }.getOrDefault("").trim()
            var translation = "（这张图片里没有识别到文字）"
            if (recognized.isNotEmpty()) {
                val chinese = if (turn.manualDirection) turn.sourceIsChinese else recognized.isMostlyChinese
                translation = runCatching { translateSentence(recognized, chinese).first }.getOrDefault("（翻译失败，可以点重试）")
            }
            store.updateTurn(sessionId, turn.id) { t ->
                t.copy(images = t.images.map {
                    if (it.id == item.id) it.copy(recognized = recognized, translation = translation, done = true) else it
                })
            }
        }
        store.updateTurn(sessionId, turn.id) { t ->
            t.copy(source = t.images.joinToString("\n") { it.recognized }, state = TurnState.DONE)
        }
        autoTitle(sessionId, "图片翻译")
    }

    /** 先用本机离线翻译，模型还没下载时用在线翻译 */
    private suspend fun translateSentence(text: String, chinese: Boolean): Pair<String, String> {
        if (OfflineTranslator.isReady()) {
            runCatching { OfflineTranslator.translate(text, chinese) }.getOrNull()?.let { return it to OfflineTranslator.NAME }
        }
        offlineDownloadable = !downloadingModel
        return OnlineTranslator.translate(text, chinese) to OnlineTranslator.NAME
    }

    fun downloadOfflineModel() {
        offlineDownloadable = false
        downloadingModel = true
        scope.launch {
            runCatching { OfflineTranslator.download() }
            downloadingModel = false
        }
    }

    // AI 优化

    /** 点“AI 优化”：打开时有之前的结果就直接用，没有才请求；再点一次关掉，显示机器翻译 */
    fun toggleAI(turnId: String) {
        val sentence = store.turn(currentId, turnId)?.sentence ?: return
        when {
            sentence.showsAI -> store.updateTurn(currentId, turnId) { it.copy(sentence = sentence.copy(aiShown = false)) }
            sentence.aiTranslation != null -> store.updateTurn(currentId, turnId) { it.copy(sentence = sentence.copy(aiShown = true)) }
            else -> {
                val sessionId = currentId
                scope.launch { optimize(sessionId, turnId) }
            }
        }
    }

    /** 换了服务商或模型后，重新用 AI 优化一次 */
    fun reoptimize(turnId: String) {
        val sessionId = currentId
        scope.launch { optimize(sessionId, turnId, force = true) }
    }

    /** 完整词条里查不到的词组，用和会话相同的方式翻译 */
    suspend fun quickTranslate(text: String): String? =
        runCatching { translateSentence(text, text.isMostlyChinese).first }.getOrNull()

    private suspend fun optimize(sessionId: String, turnId: String, force: Boolean = false) {
        val turn = store.turn(sessionId, turnId) ?: return
        val sentence = turn.sentence ?: return
        if (turn.isOptimizing) return
        if (sentence.aiTranslation != null && !force) {
            store.updateTurn(sessionId, turnId) { it.copy(sentence = sentence.copy(aiShown = true)) }
            return
        }
        store.updateTurn(sessionId, turnId) { it.copy(isOptimizing = true, aiError = null) }
        try {
            val config = AISettings.currentConfig
            val response = AITasks.calibrate(sentence.source, sentence.translation, sentence.sourceIsChinese, config)
            store.updateTurn(sessionId, turnId) { t ->
                t.copy(
                    sentence = t.sentence?.copy(
                        aiTranslation = response.text, aiShown = true, aiUsage = response.usage,
                        aiModel = "${config.provider.title} · ${config.model}",
                    ),
                    isOptimizing = false,
                )
            }
        } catch (e: Exception) {
            store.updateTurn(sessionId, turnId) { it.copy(isOptimizing = false, aiError = e.message ?: "AI 优化失败") }
        }
    }

    // 工具

    /** 没被用户命名的会话，用第一轮的原文开头当名称 */
    private fun autoTitle(sessionId: String, text: String) {
        val session = store.session(sessionId) ?: return
        if (!session.autoTitled || session.title != ChatSession.DEFAULT_TITLE) return
        val line = text.lines().firstOrNull()?.trim() ?: text
        store.updateSession(sessionId) { it.copy(title = line.take(18)) }
    }

    /** 单词或短语（查词典）；不是的才算一句话，AI 优化只针对一句话 */
    fun isWordLike(text: String): Boolean {
        if (text.any { it in "\n,.!?;，。！？；" }) return false
        if (text.containsChinese) return text.length <= 8
        return text.length <= 40 && text.split(" ").size <= 4
    }
}
