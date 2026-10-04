package com.yishulabs.qtranslator.modules

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yishulabs.qtranslator.R
import com.yishulabs.qtranslator.core.Interpreter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * 同声传译在后台继续收音：安卓要求后台用麦克风时开着“麦克风”类型的前台服务，并显示一条常驻通知。
 * 通知上显示状态和最新一句，可以暂停、继续、结束；点通知回到 app。
 */
class InterpretService : Service() {
    companion object {
        private const val CHANNEL = "interpret"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_PAUSE = "com.yishulabs.qtranslator.interpret.PAUSE"
        private const val ACTION_RESUME = "com.yishulabs.qtranslator.interpret.RESUME"
        private const val ACTION_FINISH = "com.yishulabs.qtranslator.interpret.FINISH"
        /** 点通知打开 app 时带上这个：MainActivity 可以用 handleOpen 直接回到传译页 */
        const val EXTRA_OPEN = "com.yishulabs.qtranslator.interpret.OPEN"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, InterpretService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InterpretService::class.java))
        }

        /** 从通知点进来：打开同声传译模块并回到传译页。是这种 intent 就返回 true */
        fun handleOpen(intent: Intent?): Boolean {
            if (intent?.getBooleanExtra(EXTRA_OPEN, false) != true) return false
            if (InterpretSession.isActive) {
                ModuleRouter.open(AppModule.INTERPRET, from = "notification")
                ModuleRouter.launch = ModuleRouter.Launch.Interpret(null)
            }
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "同声传译", NotificationManager.IMPORTANCE_LOW).apply {
                description = "同声传译在后台收音时显示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val ok = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }.isSuccess
        if (!ok) {
            stopSelf()
            return
        }
        // 状态、时长基准和最新一句变了就更新通知（最多一秒一次）
        scope.launch {
            snapshotFlow {
                val interpreter = InterpretSession.interpreter
                Triple(interpreter?.state, interpreter?.startedAt, interpreter?.let { InterpretSession.latestLine(it) })
            }.conflate().collect {
                if (InterpretSession.interpreter == null) {
                    stopSelf()
                } else {
                    manager.notify(NOTIFICATION_ID, notification())
                }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val interpreter = InterpretSession.interpreter
        if (interpreter == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> interpreter.pause()
            ACTION_RESUME -> interpreter.resume()
            ACTION_FINISH -> InterpretSession.finish()
        }
        return START_NOT_STICKY
    }

    /** 从最近任务里划掉 app：结束并保存，免得这段字幕丢了 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        InterpretSession.finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val interpreter = InterpretSession.interpreter
        val paused = interpreter?.state == Interpreter.State.Paused
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_interpret)
            .setContentTitle(if (paused) "同声传译 · 已暂停" else "同声传译中")
            .setContentText(interpreter?.let { InterpretSession.latestLine(it) } ?: "正在听…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        open?.let { builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
        // 计时：正在收音时显示走动的时长
        if (interpreter != null && !paused) {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - interpreter.elapsed - (InterpretSession.previousDuration * 1000).toLong())
        } else {
            builder.setShowWhen(false)
        }
        if (interpreter?.isActive == true) {
            builder.addAction(0, if (paused) "继续" else "暂停", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
        }
        builder.addAction(0, "结束", action(ACTION_FINISH, 2))
        return builder.build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, InterpretService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
