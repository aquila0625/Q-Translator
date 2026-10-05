package com.yishulabs.qtranslator.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 很小的 HTTP 工具：在 IO 线程上发请求，返回状态码和文本 */
object Http {
    class Response(val status: Int, val body: String)

    fun query(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    suspend fun get(url: String, timeoutMs: Int = 10_000, headers: Map<String, String> = emptyMap()): Response =
        request("GET", url, null, timeoutMs, headers)

    suspend fun post(url: String, body: String, timeoutMs: Int = 120_000, headers: Map<String, String> = emptyMap()): Response =
        request("POST", url, body, timeoutMs, headers)

    /**
     * 流式下载到文件，边下边回调进度（已下载, 总大小；总大小未知时为 -1）。
     * 可以顺便把内容喂给 [digest] 算校验值。状态码不是 2xx 或者下载不完整都会抛 IOException。
     */
    suspend fun download(
        url: String, target: File, digest: MessageDigest? = null, timeoutMs: Int = 30_000,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            // 要原始字节数，别让服务器压缩，这样 Content-Length 才是文件大小
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            val total = connection.contentLengthLong
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest?.update(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 100_000_000L) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total >= 0 && done != total) throw IOException("下载不完整")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(
        method: String, url: String, body: String?, timeoutMs: Int, headers: Map<String, String>,
    ): Response = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Response(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
