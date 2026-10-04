package com.yishulabs.qtranslator.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** AI 音色：OpenAI 的语音合成，更像真人。用 ChatGPT 的 API Key，按字数计费 */
object AIVoice {
    const val MODEL = "gpt-4o-mini-tts"

    /** 可选的音色：OpenAI 的 voice 名字和说明 */
    val voices = listOf(
        "nova" to "明亮的女声", "shimmer" to "柔和的女声", "coral" to "温暖的女声",
        "alloy" to "中性的声音", "echo" to "沉稳的男声", "onyx" to "低沉的男声",
    )

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 设置里选的 AI 音色；没选是 null */
    val selected: String? get() = Prefs.aiVoice.ifEmpty { null }

    /** ChatGPT 的 Key（设置里 ChatGPT 那一项填的） */
    val apiKey: String? get() = SecretStore.get("openai")?.trim()?.ifEmpty { null }

    private val baseUrl: String
        get() {
            val saved = appContext.getSharedPreferences("ai", Context.MODE_PRIVATE).getString("baseUrl.openai", null)
            return saved?.trim()?.ifEmpty { null }?.trimEnd('/') ?: "https://api.openai.com/v1"
        }

    /** 读过的句子存成 mp3 文件（按文字、音色和模型），再读不用再请求，也不再花钱 */
    private fun cacheFile(text: String, voice: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$MODEL|$voice|$text".toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(40)
        return File(File(appContext.cacheDir, "ai-voice").apply { mkdirs() }, "$name.mp3")
    }

    /** 合成一段话，返回 mp3 文件；网络或 Key 有问题时抛出异常 */
    suspend fun speak(text: String, voice: String): File = withContext(Dispatchers.IO) {
        val file = cacheFile(text, voice)
        if (file.length() > 0) return@withContext file.also { it.setLastModified(System.currentTimeMillis()) }
        val key = apiKey ?: throw IOException("没有 ChatGPT 的 API Key")
        val connection = URL("$baseUrl/audio/speech").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            val body = JSONObject()
                .put("model", MODEL).put("voice", voice).put("input", text.take(4000)).put("response_format", "mp3")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode != 200) throw IOException("AI 音色请求失败（${connection.responseCode}）")
            val partial = File(file.path + ".part")
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
            trimCache()
            file
        } finally {
            connection.disconnect()
        }
    }

    /** 缓存最多留 200 个文件，先删最久没读的 */
    private fun trimCache() {
        val files = File(appContext.cacheDir, "ai-voice").listFiles()?.filter { it.extension == "mp3" } ?: return
        if (files.size <= 200) return
        files.sortedBy { it.lastModified() }.take(files.size - 200).forEach { it.delete() }
    }
}
