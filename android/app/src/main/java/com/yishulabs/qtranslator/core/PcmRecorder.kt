package com.yishulabs.qtranslator.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 语音输入时自己收音（安卓 13 起）：同一份声音一边通过管道交给识别器，一边编码成 m4a 存下来，一边算音量。
 *
 * 收音、编码和往管道里写都在自己的线程上；管道的写端只在这个线程上换和关，主线程只交新的过来。
 * 写管道不会卡住：识别器读得慢时先攒着，攒太多（识别器根本不读）就报 onStall。
 */
internal class PcmRecorder(
    private val file: File,
    /** 每 50 毫秒的音量（0…1），在收音线程上调用 */
    private val onLevel: (Float) -> Unit,
    /** 识别器不读管道（不支持外部音源），在收音线程上调用 */
    private val onStall: () -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        /** 每次读 50 毫秒 */
        private const val CHUNK_BYTES = SAMPLE_RATE / 20 * 2
        /** 攒了 3 秒还没被读走，就当识别器不读 */
        private const val STALL_BYTES = SAMPLE_RATE * 2 * 3

        /** 一段 16 位 PCM 的音量：转成分贝再映射到 0…1，安静时接近 0（和苹果版一样） */
        fun level(bytes: ByteArray, count: Int): Float {
            val samples = count / 2
            if (samples == 0) return 0f
            var sum = 0.0
            for (i in 0 until samples) {
                val value = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768.0
                sum += value * value
            }
            val db = 20 * log10(max(sqrt(sum / samples), 0.000_01))
            return ((db + 55) / 45).toFloat().coerceIn(0f, 1f)
        }
    }

    /** 管道的写端；pipe 为 null 表示不再给识别器送声音 */
    private class Sink(val pipe: ParcelFileDescriptor?) {
        private var pending = ByteArray(CHUNK_BYTES * 4)
        private var pendingLength = 0

        /** 0：好的，1：识别器关掉了管道，2：识别器一直不读 */
        fun feed(bytes: ByteArray, count: Int): Int {
            val fd = pipe?.fileDescriptor ?: return 1
            if (pendingLength + count > pending.size) pending = pending.copyOf(max(pending.size * 2, pendingLength + count))
            System.arraycopy(bytes, 0, pending, pendingLength, count)
            pendingLength += count
            try {
                while (pendingLength > 0) {
                    val written = Os.write(fd, pending, 0, pendingLength)
                    if (written <= 0) break
                    System.arraycopy(pending, written, pending, 0, pendingLength - written)
                    pendingLength -= written
                }
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EAGAIN) return 1
            } catch (e: Exception) {
                return 1
            }
            return if (pendingLength > STALL_BYTES) 2 else 0
        }

        fun close() {
            runCatching { pipe?.close() }
        }
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    /** 主线程交过来的新写端，收音线程换上它并关掉旧的 */
    private val nextSink = AtomicReference<Sink?>(null)
    /** 文件里有没有声音；stop 之后才准 */
    @Volatile private var wroteAudio = false

    /** 开始收音；麦克风打不开时返回 false */
    @SuppressLint("MissingPermission") // 调用前已经检查过麦克风权限
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, CHUNK_BYTES * 8),
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }
        this.record = record
        running = true
        thread = Thread({ run(record) }, "voice-input").apply { start() }
        return true
    }

    /** 给新的识别器开一条管道，返回读端（交给 EXTRA_AUDIO_SOURCE）。旧管道由收音线程关掉 */
    fun newPipe(): ParcelFileDescriptor? {
        val (read, write) = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        // 写端不阻塞：识别器没在读时不能卡住收音
        runCatching {
            val flags = Os.fcntlInt(write.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(write.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }.onFailure {
            read.close()
            write.close()
            return null
        }
        // 收音线程还没拿走的旧写端没人用过，直接关掉
        nextSink.getAndSet(Sink(write))?.close()
        return read
    }

    /** 不再给识别器送声音（继续录） */
    fun detachPipe() {
        nextSink.getAndSet(Sink(null))?.close()
    }

    /** 停止并写完文件：返回文件里有没有声音（没有时文件已经删掉） */
    fun stop(): Boolean {
        running = false
        runCatching { record?.stop() }
        runCatching { thread?.join(1500) }
        record?.release()
        record = null
        thread = null
        nextSink.getAndSet(null)?.close()
        if (!wroteAudio) file.delete()
        return wroteAudio
    }

    private fun run(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val encoder = runCatching { AacWriter(file) }.getOrNull()
        var encoderOk = encoder != null
        var sink: Sink? = null
        var stalled = false
        val bytes = ByteArray(CHUNK_BYTES)
        try {
            while (running) {
                val count = record.read(bytes, 0, bytes.size)
                if (count < 0) break
                if (count == 0) continue
                nextSink.getAndSet(null)?.let { next ->
                    sink?.close()
                    sink = next.takeIf { it.pipe != null }
                    stalled = false
                }
                sink?.let {
                    when (it.feed(bytes, count)) {
                        1 -> {
                            it.close()
                            sink = null
                        }
                        2 -> if (!stalled) {
                            stalled = true
                            onStall()
                        }
                    }
                }
                if (encoderOk) encoderOk = runCatching { encoder!!.write(bytes, count) }.isSuccess
                onLevel(level(bytes, count))
            }
        } finally {
            sink?.close()
            wroteAudio = encoderOk && encoder != null && encoder.finish()
            if (!encoderOk) encoder?.release()
        }
    }
}

/** 把 16 位单声道 PCM 编码成 AAC，存成 m4a */
private class AacWriter(file: File) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var wrote = false
    private var samples = 0L

    init {
        file.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, PcmRecorder.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    private val timeUs: Long get() = samples * 1_000_000 / PcmRecorder.SAMPLE_RATE

    fun write(bytes: ByteArray, count: Int) {
        var offset = 0
        var tries = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(false)
                if (++tries > 50) error("编码器没有空闲的输入缓冲区")
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: error("编码器输入缓冲区为空")
            buffer.clear()
            val size = minOf(buffer.remaining(), count - offset)
            buffer.put(bytes, offset, size)
            codec.queueInputBuffer(index, 0, size, timeUs, 0)
            samples += size / 2
            offset += size
        }
        drain(false)
    }

    /** 写完：返回文件里有没有声音 */
    fun finish(): Boolean {
        runCatching {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        }
        release()
        return wrote
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (started) runCatching { muxer.stop() }.onFailure { wrote = false }
        runCatching { muxer.release() }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 100) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && started && buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                        wrote = true
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
