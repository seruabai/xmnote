package com.purenote.local.core

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记内录音：输出 m4a（AAC）到应用私有目录，文件名 aud_ 前缀（与图片同目录，靠前缀区分）。
 * 状态机：IDLE → RECORDING → (stop → 文件名 | cancel → 删除)
 */
class AudioRecorder(private val context: Context) {

    enum class State { IDLE, RECORDING }

    var state: State = State.IDLE
        private set
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0

    val elapsedMs: Long
        get() = if (state == State.RECORDING) System.currentTimeMillis() - startedAt else 0L

    fun start(): Boolean {
        if (state == State.RECORDING) return true
        val dir = ImageStore.imagesDir(context)
        val name = "aud_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".m4a"
        val file = File(dir, name)
        return runCatching {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            startedAt = System.currentTimeMillis()
            state = State.RECORDING
            true
        }.getOrDefault(false)
    }

    /** 结束并返回文件名；失败/未在录返回 null */
    fun stop(): String? {
        val r = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        state = State.IDLE
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        return if (ok && file != null && file.exists() && file.length() > 0) file.name else null
    }

    fun cancel() {
        val r = recorder
        recorder = null
        val file = outputFile
        outputFile = null
        state = State.IDLE
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        file?.let { runCatching { it.delete() } }
    }
}
