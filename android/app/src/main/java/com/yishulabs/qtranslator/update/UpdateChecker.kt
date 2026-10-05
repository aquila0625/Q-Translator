package com.yishulabs.qtranslator.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.yishulabs.qtranslator.BuildConfig
import com.yishulabs.qtranslator.analytics.Analytics
import com.yishulabs.qtranslator.core.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 应用内检查更新（官网 APK、adb 等非 Google Play 来源）。
 * 接口：GET {UPDATE_BASE_URL}/api/releases/latest?platform=android&abi=...，服务器的 versionCode 比本机大才算新版本。
 * Google Play 来源完全不检查、不提示（由商店更新）。
 */
object UpdateChecker {
    class Release(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val url: String,
        val sha256: String?,
        val size: Long?,
        val mandatory: Boolean,
    )

    /** 更新对话框所处的阶段 */
    enum class Phase { PROMPT, DOWNLOADING, READY, PERMISSION, FAILED }

    /** 手动检查的结果；BUSY 表示正在检查或更新对话框已经开着，不用再提示 */
    enum class CheckResult { NEW_VERSION, UP_TO_DATE, FAILED, BUSY }

    private sealed interface Fetched {
        class Found(val release: Release) : Fetched
        data object None : Fetched
        data object Failed : Fetched
    }

    private const val PLAY_STORE = "com.android.vending"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val THROTTLE_MS = 12 * 60 * 60 * 1000L
    private const val LAST_CHECK_KEY = "update.lastCheck"
    private const val SKIPPED_KEY = "update.skippedCode"

    private lateinit var appContext: Context
    private lateinit var store: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null
    private var apkFile: File? = null
    private var autoChecked = false

    /** 这个安装来源要不要做应用内更新（Google Play 来源不要） */
    var enabled by mutableStateOf(false)
        private set

    /** 正在手动检查 */
    var checking by mutableStateOf(false)
        private set

    /** 有新版本时不为空，更新对话框就显示出来 */
    var release by mutableStateOf<Release?>(null)
        private set
    var phase by mutableStateOf(Phase.PROMPT)
        private set
    var downloaded by mutableLongStateOf(0L)
        private set
    var total by mutableLongStateOf(0L)
        private set
    var failure by mutableStateOf("")
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        store = appContext.getSharedPreferences("update", Context.MODE_PRIVATE)
        enabled = !isFromGooglePlay()
        // 上次下载的安装包用完就清掉（更新成功后进程重启，会走到这里）
        Thread { File(appContext.cacheDir, "updates").deleteRecursively() }.start()
    }

    @Suppress("DEPRECATION")
    private fun isFromGooglePlay(): Boolean {
        val pm = appContext.packageManager
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(appContext.packageName).installingPackageName
            else pm.getInstallerPackageName(appContext.packageName)
        } catch (_: Exception) {
            null
        }
        return installer == PLAY_STORE
    }

    /** 和本机安装的 APK 对应的 ABI：32 位进程就是 armeabi-v7a，其它按 arm64-v8a */
    private fun abi() = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"

    private suspend fun fetch(): Fetched = try {
        val url = BuildConfig.UPDATE_BASE_URL + "/api/releases/latest?" + Http.query("platform" to "android", "abi" to abi())
        val response = Http.get(url, timeoutMs = 10_000)
        when (response.status) {
            200 -> parse(response.body)?.let { Fetched.Found(it) } ?: Fetched.Failed
            404 -> Fetched.None
            else -> Fetched.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Fetched.Failed
    }

    private fun parse(text: String): Release? = try {
        val o = JSONObject(text)
        val url = o.optString("url").trim()
        val code = o.optInt("versionCode", -1)
        if (url.isEmpty() || code < 0) null else Release(
            version = o.optString("version").ifBlank { code.toString() },
            versionCode = code,
            notes = o.optString("notes").trim(),
            url = url,
            sha256 = if (o.isNull("sha256")) null else o.optString("sha256").trim().lowercase().ifEmpty { null },
            size = if (o.isNull("size")) null else o.optLong("size", -1).takeIf { it > 0 },
            mandatory = o.optBoolean("mandatory", false),
        )
    } catch (_: Exception) {
        null
    }

    private fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    private fun markChecked() = store.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()

    private fun skipped(r: Release) = !r.mandatory && store.getInt(SKIPPED_KEY, -1) == r.versionCode

    /** 启动后在后台检查：12 小时内查过就跳过，跳过的版本不提示，网络失败静默 */
    fun checkAutomatically() {
        if (!enabled || autoChecked) return
        autoChecked = true
        val elapsed = System.currentTimeMillis() - store.getLong(LAST_CHECK_KEY, 0L)
        if (elapsed in 0 until THROTTLE_MS) return
        scope.launch {
            when (val result = fetch()) {
                is Fetched.Found -> {
                    markChecked()
                    if (release == null && isNewer(result.release) && !skipped(result.release)) show(result.release)
                }
                Fetched.None -> markChecked()
                Fetched.Failed -> {}
            }
        }
    }

    /** 设置里手动检查：不看 12 小时节流，也不管跳过的版本 */
    suspend fun checkManually(): CheckResult {
        if (!enabled || checking || release != null) return CheckResult.BUSY
        checking = true
        try {
            return when (val result = fetch()) {
                is Fetched.Found -> {
                    markChecked()
                    if (isNewer(result.release)) {
                        show(result.release)
                        CheckResult.NEW_VERSION
                    } else {
                        CheckResult.UP_TO_DATE
                    }
                }
                Fetched.None -> {
                    markChecked()
                    CheckResult.UP_TO_DATE
                }
                Fetched.Failed -> CheckResult.FAILED
            }
        } finally {
            checking = false
        }
    }

    private fun show(r: Release) {
        release = r
        phase = Phase.PROMPT
        downloaded = 0
        total = r.size ?: 0
        failure = ""
        Analytics.track(Analytics.Event.UPDATE_PROMPT, mapOf("version" to r.version))
    }

    /** “稍后”：关掉对话框，下次检查还会提示 */
    fun dismiss() {
        downloadJob?.cancel()
        release = null
    }

    /** “跳过此版本”：之后同一版本不再自动提示 */
    fun skip() {
        val r = release ?: return
        store.edit().putInt(SKIPPED_KEY, r.versionCode).apply()
        Analytics.track(Analytics.Event.UPDATE_SKIP, mapOf("version" to r.version))
        dismiss()
    }

    /** “立即更新”按钮 */
    fun accept() {
        val r = release ?: return
        Analytics.track(Analytics.Event.UPDATE_ACCEPT, mapOf("version" to r.version))
        startDownload()
    }

    /** 下载中点“取消”：回到提示 */
    fun cancelDownload() {
        downloadJob?.cancel()
        phase = Phase.PROMPT
    }

    /** 下载（失败后“重试”也走这里） */
    fun startDownload() {
        val r = release ?: return
        downloadJob?.cancel()
        phase = Phase.DOWNLOADING
        downloaded = 0
        total = r.size ?: 0
        failure = ""
        downloadJob = scope.launch {
            var file: File? = null
            try {
                val dir = File(appContext.cacheDir, "updates")
                dir.deleteRecursively()
                dir.mkdirs()
                val safe = r.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
                file = File(dir, "Q-Translator-$safe.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                Http.download(r.url, file, digest) { done, length ->
                    downloaded = done
                    if (length > 0) total = length
                }
                val expected = r.sha256
                if (!expected.isNullOrEmpty()) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != expected) {
                        file.delete()
                        fail("下载的文件校验失败，请重试")
                        return@launch
                    }
                }
                apkFile = file
                install()
            } catch (e: CancellationException) {
                file?.delete()
                throw e
            } catch (_: Exception) {
                file?.delete()
                fail("下载失败，请检查网络后重试")
            }
        }
    }

    private fun fail(message: String) {
        failure = message
        phase = Phase.FAILED
    }

    /** 调起系统安装器；还没允许“安装未知应用”时先进入 PERMISSION 阶段 */
    fun install() {
        val file = apkFile?.takeIf { it.exists() }
        if (file == null) {
            startDownload()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !appContext.packageManager.canRequestPackageInstalls()) {
            phase = Phase.PERMISSION
            return
        }
        try {
            val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            // 停在“已下载”：用户在安装器里取消后回来，还能再点“安装”
            phase = Phase.READY
        } catch (_: ActivityNotFoundException) {
            fail("无法打开系统安装器")
        } catch (_: Exception) {
            fail("无法打开安装包，请重试")
        }
    }

    /** 跳到系统设置，让用户允许快译安装应用 */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + appContext.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
            // 个别系统没有这个页面，退而求其次打开应用详情
            try {
                appContext.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + appContext.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 回到前台时调用：在设置里允许之后，自动继续安装 */
    fun onResume() {
        if (release == null || phase != Phase.PERMISSION) return
        if (Build.VERSION.SDK_INT < 26 || appContext.packageManager.canRequestPackageInstalls()) install()
    }

    /** 权限说明里点“取消”：回到“已下载”，可以之后再点安装 */
    fun cancelPermission() {
        phase = Phase.READY
    }
}
