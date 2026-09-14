package com.purenote.local.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.purenote.local.data.AttachmentStore
import java.io.File

/**
 * 图片落盘：统一复制到应用私有目录，避免依赖外部 Content Uri 的生命周期。
 *
 * 规范 §10 改造要点（对照原实现）：
 *  - 命名加随机后缀：`yyyyMMdd_HHmmss_SSS` 单独用会在同一毫秒内撞名并互相覆盖；
 *  - 先写暂存、校验大小后**不覆盖地**发布，写一半失败不再留下"看起来正常"的残缺图片；
 *  - 检查 `Bitmap.compress` 的返回值，编码失败不再返回一个可用文件名；
 *  - 相机输出不再原地覆盖自己，落盘后清理临时文件；
 *  - 新增 [importFromUriDetailed]/[saveBitmapDetailed] 返回具体失败原因，
 *    旧调用方仍可用折叠成 null 的老接口（渐进迁移）。
 *
 * 文件名前缀（img_/aud_/draw_）保持不变：正文里存的就是文件名，
 * 而 `NoteMarkup.audioNames` 依赖 `aud_` 前缀区分录音，
 * 改成纯 UUID 需要再动一次正文格式与迁移。
 */
object ImageStore {

    const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 88

    sealed interface ImportOutcome {
        data class Ok(val fileName: String) : ImportOutcome
        data class Failed(val detail: String) : ImportOutcome
    }

    fun imagesDir(context: Context): File =
        File(context.filesDir, "images").apply { mkdirs() }

    private fun store(context: Context) = AttachmentStore.forApp(context)

    /** 兼容旧调用方：失败原因折叠为 null。新代码请用 [importFromUriDetailed]。 */
    fun importFromUri(context: Context, uri: Uri): String? =
        (importFromUriDetailed(context, uri) as? ImportOutcome.Ok)?.fileName

    fun importFromUriDetailed(context: Context, uri: Uri): ImportOutcome = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            ImportOutcome.Failed("无法读取图片来源的尺寸")
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = computeSample(bounds.outWidth, bounds.outHeight) }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bitmap == null) {
                ImportOutcome.Failed("图片解码失败")
            } else {
                val outcome = encodeToPublished(context, bitmap, prefix = "img") { it.recycle() }
                outcome
            }
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        ImportOutcome.Failed("导入图片失败：" + (t.message ?: t::class.java.simpleName))
    }

    fun fileFor(context: Context, fileName: String): File = File(imagesDir(context), fileName)

    fun deleteFile(context: Context, fileName: String) {
        runCatching { File(imagesDir(context), fileName).delete() }
    }

    /**
     * 相机的输出目标。仍放在 images/ 下（FileProvider 的路径映射依赖它），
     * 但由 [importCaptured] 在发布后清理，失败时不会污染正式附件目录。
     */
    fun newCameraTarget(context: Context): File =
        File(imagesDir(context), store(context).newFileName("cam", "jpg"))

    /** 相机输出可能很大，导入时再压一遍 */
    fun importCaptured(context: Context, file: File): String? =
        (importCapturedDetailed(context, file) as? ImportOutcome.Ok)?.fileName

    fun importCapturedDetailed(context: Context, file: File): ImportOutcome = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = computeSample(bounds.outWidth, bounds.outHeight) },
        )
        val outcome = if (bitmap == null) {
            ImportOutcome.Failed("相机图片解码失败")
        } else {
            // 压缩到暂存区再发布：原实现是 file.outputStream().use { compress }，
            // 直接覆盖正在读取的同一份文件。
            encodeToPublished(context, bitmap, prefix = "img") { it.recycle() }
        }
        file.delete()   // 无论成功与否都清理相机临时文件
        outcome
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        file.delete()
        ImportOutcome.Failed("导入相机图片失败：" + (t.message ?: t::class.java.simpleName))
    }

    /** 供速记浮窗等非 Compose 场景使用：把 bitmap 存为新图片文件 */
    fun saveBitmap(context: Context, bitmap: Bitmap): String? =
        (saveBitmapDetailed(context, bitmap) as? ImportOutcome.Ok)?.fileName

    fun saveBitmapDetailed(context: Context, bitmap: Bitmap): ImportOutcome = try {
        encodeToPublished(context, bitmap, prefix = "quick") { }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        ImportOutcome.Failed("保存图片失败：" + (t.message ?: t::class.java.simpleName))
    }

    /** 压缩 -> 暂存 -> 校验 -> 不覆盖发布。返回正式文件名。 */
    private fun encodeToPublished(
        context: Context,
        bitmap: Bitmap,
        prefix: String,
        onFinally: (Bitmap) -> Unit,
    ): ImportOutcome {
        val store = store(context)
        val staging = File(context.cacheDir, "attachment-staging").apply { mkdirs() }
        // 只生成一次名字：暂存用 <name>.tmp，发布时就叫 <name>
        val fileName = store.newFileName(prefix, "jpg")
        val staged = File(staging, "$fileName.tmp")
        try {
            val ok = staged.outputStream().use { out ->
                // 规范 §10：必须检查编码返回值，失败不能当成成功
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!ok) return ImportOutcome.Failed("图片编码失败（compress 返回 false）")
        } finally {
            onFinally(bitmap)
        }
        return when (val result = store.publishAs(staged, fileName)) {
            is AttachmentStore.Outcome.Ok -> ImportOutcome.Ok(result.published.fileName)
            is AttachmentStore.Outcome.Failed -> {
                staged.delete()
                ImportOutcome.Failed(result.detail)
            }
        }
    }

    private fun computeSample(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DIMENSION * 2) sample *= 2
        return sample
    }
}
