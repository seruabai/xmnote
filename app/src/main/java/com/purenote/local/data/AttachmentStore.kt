package com.purenote.local.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 附件存储（规范 §10）：原件不可变、UUID 命名、先暂存后发布、发布前校验。
 *
 * 对照原实现（`core/ImageStore.kt`）的四个问题：
 *  1. 用 `yyyyMMdd_HHmmss_SSS` 时间戳命名 —— 同一秒内两次导入会撞名并互相覆盖；
 *  2. 直接写目标文件 —— 写一半失败会留下一个"看起来正常"的残缺图片；
 *  3. `bitmap.compress` 的返回值没检查，编码失败仍返回文件名；
 *  4. 整段包在 `runCatching{}.getOrNull()` 里，调用方无法区分"没图"和"写盘失败"。
 *
 * 本类刻意不依赖 ContentResolver / Bitmap：解码与压缩由调用方完成，
 * 这里只负责"字节流 → 校验过的不可变文件"这段最容易出错的部分，
 * 因而可以在 JVM 上直接测试（不需要设备）。
 */
class AttachmentStore(
    private val stagingDir: File,
    private val publishedDir: File,
) {

    /** 已发布的附件：[attachmentId] 跨设备稳定，[fileName] 是磁盘上的实际名字 */
    data class Published(
        val attachmentId: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    sealed interface Outcome {
        data class Ok(val published: Published) : Outcome
        data class Failed(val detail: String) : Outcome
    }

    /**
     * 把来源流写入暂存区，同时计算大小与 SHA-256。
     * 失败时清理暂存文件，绝不留下半成品。
     */
    fun stage(source: InputStream, extension: String, attachmentId: String = newId()): Outcome {
        val ext = normalizeExtension(extension)
        val staged = File(stagingDir.apply { mkdirs() }, "$attachmentId.$ext.tmp")
        if (staged.exists() && !staged.delete()) return Outcome.Failed("无法清理同名暂存文件")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        size += read
                    }
                    output.flush()
                }
            }
            if (size == 0L) {
                staged.delete()
                return Outcome.Failed("来源为空，未写入任何字节")
            }
            Outcome.Ok(Published(attachmentId, staged.name, size, digest.digest().toHex()))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            staged.delete()
            Outcome.Failed("写入暂存文件失败：" + (t.message ?: t::class.java.simpleName))
        }
    }

    /**
     * 把暂存文件发布为正式附件。
     * 发布是**不覆盖**的：目标名已被占用时换一个新 ID 重来，绝不覆盖既有附件。
     * 发布后再次核对大小，防止搬移过程中出现截断。
     */
    fun publish(staged: File, attachmentId: String, extension: String): Outcome {
        if (!staged.exists()) return Outcome.Failed("暂存文件不存在，可能已被清理")
        val expectedSize = staged.length()
        if (expectedSize == 0L) return Outcome.Failed("暂存文件为空")

        publishedDir.mkdirs()
        val ext = normalizeExtension(extension)
        var id = attachmentId
        var target = File(publishedDir, "$id.$ext")
        var attempts = 0
        while (target.exists()) {
            if (++attempts > 5) return Outcome.Failed("连续多次命名冲突，放弃发布")
            id = newId()
            target = File(publishedDir, "$id.$ext")
        }

        if (!staged.renameTo(target)) {
            // 跨文件系统等情况下 renameTo 会失败，退回复制 + 删除
            return try {
                staged.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                staged.delete()
                verifyPublished(target, expectedSize, id)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                target.delete()
                Outcome.Failed("发布附件失败：" + (t.message ?: t::class.java.simpleName))
            }
        }
        return verifyPublished(target, expectedSize, id)
    }

    private fun verifyPublished(target: File, expectedSize: Long, id: String): Outcome {
        val actual = target.length()
        if (actual != expectedSize) {
            target.delete()
            return Outcome.Failed("发布后大小不一致（期望 $expectedSize，实际 $actual），已丢弃")
        }
        return Outcome.Ok(Published(id, target.name, actual, sha256Of(target)))
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * 以调用方指定的文件名发布（仍**绝不覆盖**：目标已存在则失败）。
     *
     * 之所以保留调用方命名：正文里存的就是文件名，而 `img_`/`aud_`/`draw_` 前缀
     * 是当前正文格式的一部分（`NoteMarkup.audioNames` 靠 `aud_` 前缀区分录音），
     * 改成纯 UUID 需要再动一次正文格式与迁移——那正是上一轮出问题的地方。
     * 这里只取 AttachmentStore 的安全机制：先暂存、校验后、不覆盖地发布。
     */
    fun publishAs(staged: File, fileName: String): Outcome {
        if (!staged.exists()) return Outcome.Failed("暂存文件不存在，可能已被清理")
        val expectedSize = staged.length()
        if (expectedSize == 0L) return Outcome.Failed("暂存文件为空")
        publishedDir.mkdirs()
        val target = File(publishedDir, fileName)
        if (target.exists()) return Outcome.Failed("目标文件已存在，拒绝覆盖：$fileName")
        if (!staged.renameTo(target)) {
            return try {
                staged.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                staged.delete()
                verifyPublished(target, expectedSize, fileName)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                target.delete()
                Outcome.Failed("发布附件失败：" + (t.message ?: t::class.java.simpleName))
            }
        }
        return verifyPublished(target, expectedSize, fileName)
    }

    /** 生成唯一文件名，保留既有前缀约定。时间戳单独用会在同一毫秒内撞名。 */
    fun newFileName(prefix: String, extension: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
            .format(java.util.Date())
        val unique = UUID.randomUUID().toString().replace("-", "").take(6)
        return "$prefix${stamp}_$unique.${normalizeExtension(extension)}"
    }

    fun resolve(fileName: String): File = File(publishedDir, fileName)

    /** 释放未被发布的暂存文件（未完成的项受控清理，规范 §10） */
    fun discardStaged(staged: File) {
        if (staged.exists()) staged.delete()
    }

    private fun normalizeExtension(raw: String): String {
        val cleaned = raw.trim().trimStart('.').lowercase().filter { it.isLetterOrDigit() }
        return if (cleaned.isEmpty()) "bin" else cleaned.take(8)
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        fun forApp(context: Context): AttachmentStore = AttachmentStore(
            stagingDir = File(context.cacheDir, "attachment-staging"),
            publishedDir = File(context.filesDir, "images"),
        )
    }
}
