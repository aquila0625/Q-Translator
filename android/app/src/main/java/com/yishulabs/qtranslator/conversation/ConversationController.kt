package com.yishulabs.qtranslator.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yishulabs.qtranslator.ai.AISettings
import com.yishulabs.qtranslator.ai.AITasks
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.ImageText
import com.yishulabs.qtranslator.core.OfflineTranslator
import com.yishulabs.qtranslator.core.OnlineTranslator
import com.yishulabs.qtranslator.core.Prefs
import com.yishulabs.qtranslator.core.SentenceResult
import com.yishulabs.qtranslator.core.Speaker
import com.yishulabs.qtranslator.core.Speech
import com.yishulabs.qtranslator.core.Suggestion
import com.yishulabs.qtranslator.core.VoiceInput
import com.yishulabs.qtranslator.core.Youdao
import com.yishulabs.qtranslator.core.containsChinese
import com.yishulabs.qtranslator.core.isMostlyChinese
import com.yishulabs.qtranslator.modules.ModuleRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Direction(val label: String) { AUTO("自动"), EN_TO_ZH("英→中"), ZH_TO_EN("中→英") }

/** 会话页的大脑：发送、翻译、编辑原文、AI 优化、图片处理。 */
class ConversationController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = ConversationStore(context, scope)

    var currentId by mutableStateOf("")
        private set
    private var draftText by mutableStateOf("")
    var draft: String
        get() = draftText
        set(value) {
            draftText = value
            // 把输入框清空了：语音输入留下的原声也不要了
            if (value.isBlank()) pendingAudio?.let {
                store.deleteAudioFile(it.name)
                pendingAudio = null
            }
        }
    var direction by mutableStateOf(Direction.AUTO)
        private set
    /** 离线翻译模型可以下载但还没下载 */
    var offlineDownloadable by mutableStateOf(false)
        private set
    var downloadingModel by mutableStateOf(false)
        private set
    /** 选好还没发出去的图片，显示在输入框上方，可以预览、排序、删除 */
    var pendingImages by mutableStateOf(listOf<PendingImage>())
        private set
    /** 语音输入留下的原声，跟着下一次发送的文字一起保存；把输入框清空就丢掉 */
    var pendingAudio by mutableStateOf<PendingAudio?>(null)

    /** 待发图片最近一次的来源（统计用） */
    private var pendingImageSource = "other"
    /** 旋转要读图、转、写回文件：一张一张来，连点几次也不会转乱 */
    private val rotation = Mutex()

    /** 一张待发的图片；thumb 是输入框上方显示用的小图 */
    class PendingImage(val bitmap: Bitmap) {
        val id: String = UUID.randomUUID().toString()
        val thumb: Bitmap = ConversationStore.scaled(bitmap, 240)
    }

    /** 语音输入录下的原声：文件名（filesDir/audio 下）和时长（秒） */
    data class PendingAudio(val name: String, val duration: Double)

    init {
        currentId = store.sessions.maxByOrNull { it.updatedAt }?.id
            ?: store.createSession(null, null, AISettings.autoCalibrate).id
    }

    val current: ChatSession? get() = store.session(currentId)

    // 会话

    fun select(id: String) {
        // 选了一个会话：回到翻译页（关掉正在看的模块）
        ModuleRouter.close()
        currentId = id
        direction = Direction.AUTO
    }

    fun newSession(title: String? = null, sceneId: String? = null, aiEnabled: Boolean? = null) {
        ModuleRouter.close()
        // 当前会话还是空的、也没起名，就直接复用它（移到要的场景里）
        val now = current
        if (title == null && now != null && now.turns.isEmpty() && now.autoTitled) {
            store.moveSession(now.id, sceneId)
            return
        }
        val session = store.createSession(title, sceneId, aiEnabled ?: AISettings.autoCalibrate)
        Analytics.track(Analytics.Event.SESSION_NEW, mapOf("in_scene" to if (sceneId == null) "no" else "yes"))
        select(session.id)
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

    /** 语音输入按翻译方向决定识别哪种语言；“自动”时用上次说的语言 */
    val voiceLanguage: VoiceInput.Language?
        get() = when (direction) {
            Direction.AUTO -> null
            Direction.EN_TO_ZH -> VoiceInput.Language.ENGLISH
            Direction.ZH_TO_EN -> VoiceInput.Language.CHINESE
        }

    private fun sourceIsChinese(text: String) = when (direction) {
        Direction.AUTO -> text.isMostlyChinese
        Direction.EN_TO_ZH -> false
        Direction.ZH_TO_EN -> true
    }

    // 待发的图片

    /** 选好的图片先放在输入框上方，不直接发送。source：camera / photos / files / paste / other，只用于统计 */
    fun attachImages(bitmaps: List<Bitmap>, source: String = "other") {
        if (bitmaps.isEmpty()) return
        pendingImages = pendingImages + bitmaps.map { PendingImage(it) }
        pendingImageSource = source
    }

    fun removePending(id: String) {
        pendingImages = pendingImages.filter { it.id != id }
    }

    /** 拖动排序：把一张图挪到第 index 个位置 */
    fun movePending(id: String, index: Int) {
        val item = pendingImages.firstOrNull { it.id == id } ?: return
        val rest = pendingImages.filter { it.id != id }
        pendingImages = rest.toMutableList().apply { add(index.coerceIn(0, rest.size), item) }
    }

    // 发送

    /** 发送输入框里的内容。有待发的图片时发图片，输入框里的文字是给 AI 的要求（关着 AI 时不能输入） */
    fun send() {
        if (pendingImages.isNotEmpty()) {
            val aiOn = current?.aiEnabled == true
            val instruction = if (aiOn) draft.trim() else ""
            val images = pendingImages.map { it.bitmap }
            pendingImages = emptyList()
            draft = ""
            Analytics.track(
                Analytics.Event.IMAGE_TRANSLATE,
                mapOf(
                    "source" to pendingImageSource, "count" to bucket(images.size, listOf(1, 3, 6)),
                    "ai" to if (aiOn) "on" else "off", "instruction" to if (instruction.isEmpty()) "no" else "yes",
                ),
            )
            sendImages(images, instruction.ifEmpty { null })
            return
        }
        val text = draft
        if (text.isBlank()) return
        // 先取走原声再清空输入框，不然清空时会把它删掉
        val audio = pendingAudio
        pendingAudio = null
        draft = ""
        sendText(text, audio?.name, audio?.duration)
    }

    /** 直接翻译一段文字（输入框、语音输入、分享进来的）。
     *  audioFile / audioDuration：语音输入时录下的原声（在 filesDir/audio 下），之后可以回放 */
    fun sendText(text: String, audioFile: String? = null, audioDuration: Double? = null) {
        val source = text.trim()
        if (source.isEmpty()) return
        val turn = Turn(
            source = source, sourceIsChinese = sourceIsChinese(source), manualDirection = direction != Direction.AUTO,
            audioFile = audioFile, audioDuration = audioDuration,
        )
        val attributes = mapOf(
            "direction" to if (turn.sourceIsChinese) "zh2en" else "en2zh", "voice" to if (audioFile == null) "no" else "yes",
            "ai" to if (current?.aiEnabled == true) "on" else "off",
        )
        if (isWordLike(source)) {
            Analytics.track(Analytics.Event.WORD_LOOKUP, attributes)
        } else {
            Analytics.track(Analytics.Event.TEXT_TRANSLATE, attributes + ("length" to bucket(source.length, listOf(40, 200, 1000))))
        }
        val sessionId = currentId
        store.appendTurn(turn, sessionId)
        scope.launch { process(sessionId, turn.id) }
    }

    /** 一次发送多张图片，作为同一轮；instruction 是给 AI 的要求 */
    fun sendImages(bitmaps: List<Bitmap>, instruction: String? = null) {
        if (bitmaps.isEmpty()) return
        val sessionId = currentId
        val chinese = direction == Direction.ZH_TO_EN
        val manual = direction != Direction.AUTO
        scope.launch {
            val files = withContext(Dispatchers.IO) { bitmaps.mapNotNull { store.saveImage(it) } }
            if (files.isEmpty()) return@launch
            val turn = Turn(
                source = "", images = files.map { TurnImage(fileName = it) },
                sourceIsChinese = chinese, manualDirection = manual, instruction = instruction,
            )
            store.appendTurn(turn, sessionId)
            process(sessionId, turn.id)
        }
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
        store.updateTurn(sessionId, turnId) { t ->
            t.copy(state = TurnState.WORKING, errorMessage = null, images = t.images.map { it.copy(done = false) })
        }
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

    /** 只重新识别和翻译这一张图片（识别失败、没识别到文字或翻译不完整时用） */
    fun reprocessImage(turnId: String, imageId: String) {
        Analytics.track(Analytics.Event.IMAGE_RECOGNIZE_AGAIN)
        val sessionId = currentId
        store.updateTurn(sessionId, turnId) { t ->
            t.copy(
                images = t.images.map {
                    if (it.id == imageId) it.copy(recognized = "", translation = "", blocks = null, failed = null, done = false) else it
                },
                errorMessage = null, state = TurnState.WORKING,
            )
        }
        scope.launch { process(sessionId, turnId) }
    }

    /** 把图片顺时针转 90°：译文跟着图片一起转，不用重新识别（转歪了的照片想按新方向重新识别，点“重新识别”） */
    fun rotateImage(turnId: String, imageId: String) {
        val sessionId = currentId
        scope.launch {
            rotation.withLock {
                val item = store.turn(sessionId, turnId)?.images?.firstOrNull { it.id == imageId && it.done } ?: return@withLock
                val rotated = withContext(Dispatchers.IO) {
                    val bitmap = store.image(item.fileName) ?: return@withContext false
                    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
                    store.replaceImage(item.fileName, turned)
                    true
                }
                if (!rotated) return@withLock
                Analytics.track(Analytics.Event.IMAGE_ROTATE)
                store.updateTurn(sessionId, turnId) { t ->
                    t.copy(images = t.images.map { image ->
                        if (image.id == imageId) image.copy(blocks = image.blocks?.map { it.rotatedClockwise() }) else image
                    })
                }
            }
        }
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

    /** 逐张识别，按段翻译，译文记下位置，显示时覆盖在原文上。开着 AI 时整张图的几段一起交给 AI，可以带用户的要求 */
    private suspend fun processImages(sessionId: String, turn: Turn) {
        val useAI = store.session(sessionId)?.aiEnabled == true && AISettings.isConfigured
        for (item in turn.images.filter { !it.done }) {
            val bitmap = withContext(Dispatchers.IO) { store.image(item.fileName) } ?: continue
            var failed = false
            val found = try {
                ImageText.recognizeBlocks(bitmap)
            } catch (e: Exception) {
                failed = true
                emptyList()
            }
            val recognized = found.joinToString("\n") { it.text }
            val chinese = if (turn.manualDirection) turn.sourceIsChinese else recognized.isMostlyChinese
            var translations = List(found.size) { "" }
            if (found.isNotEmpty()) {
                val byAI = if (useAI) {
                    runCatching { translateImageBlocks(found.map { it.text }, turn.instruction, !chinese, AISettings.currentConfig) }.getOrNull()
                } else {
                    null
                }
                translations = byAI ?: found.map { block ->
                    runCatching { translateSentence(block.text, chinese).first }.getOrElse {
                        failed = true
                        ""
                    }
                }
            }
            val colors = ImageText.backgroundColors(bitmap, found.map { it.rect })
            val blocks = found.mapIndexed { i, block ->
                ImageBlock(
                    text = block.text, translation = translations[i],
                    x = block.rect.left.toDouble(), y = block.rect.top.toDouble(),
                    width = block.rect.width().toDouble(), height = block.rect.height().toDouble(),
                    lines = block.lines, background = colors[i],
                )
            }
            val incomplete = failed || (found.isNotEmpty() && translations.all { it.isEmpty() })
            store.updateTurn(sessionId, turn.id) { t ->
                t.copy(images = t.images.map {
                    if (it.id != item.id) it else it.copy(
                        recognized = recognized, blocks = blocks,
                        translation = if (found.isEmpty()) "（这张图片里没有识别到文字）" else translations.filter { s -> s.isNotEmpty() }.joinToString("\n"),
                        failed = if (incomplete) true else null, done = true,
                    )
                })
            }
        }
        store.updateTurn(sessionId, turn.id) { t ->
            t.copy(source = t.images.joinToString("\n") { it.recognized }, state = TurnState.DONE)
        }
        autoTitle(sessionId, turn.instruction ?: "图片翻译")
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
        val config = AISettings.currentConfig
        Analytics.track(
            Analytics.Event.AI_OPTIMIZE,
            mapOf("trigger" to if (force) "manual" else "auto", "provider" to config.provider.name.lowercase()),
        )
        try {
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

    /** 统计里的数量分档，例如 [1, 3, 6] → “≤1”“2-3”“4-6”“>6”；和苹果版 Analytics.bucket 一致 */
    private fun bucket(value: Int, edges: List<Int>): String {
        edges.forEachIndexed { i, edge ->
            if (value <= edge) return if (i == 0) "≤$edge" else "${edges[i - 1] + 1}-$edge"
        }
        return ">${edges.lastOrNull() ?: 0}"
    }
}
