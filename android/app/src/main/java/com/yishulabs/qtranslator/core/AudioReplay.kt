package com.yishulabs.qtranslator.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** 回放语音输入时录下的原声（filesDir/audio 下）。同一段再点一次就是停止 */
object AudioReplay {
    /** 正在放的文件名；界面据此把“回放原声”换成“停止” */
    var playing by mutableStateOf<String?>(null)
        private set

    private lateinit var dir: File
    private var player: MediaPlayer? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "audio")
    }

    fun toggle(name: String) {
        if (playing == name) stop() else play(name)
    }

    fun play(name: String) {
        stop()
        // 别的朗读先停下，两个声音不要叠在一起
        Speaker.stop()
        val file = File(dir, name)
        if (!file.exists()) return
        val media = MediaPlayer()
        player = media
        playing = name
        media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        media.setOnCompletionListener { if (player === it) stop() }
        media.setOnErrorListener { mp, _, _ ->
            if (player === mp) stop()
            true
        }
        try {
            media.setDataSource(file.path)
            media.prepare()
            media.start()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
    }
}
