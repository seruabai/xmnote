package com.purenote.local.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 图片落盘：统一复制到应用私有目录，避免依赖外部 Content Uri 的生命周期 */
object ImageStore {

    const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 88

    fun imagesDir(context: Context): File =
        File(context.filesDir, "images").apply { mkdirs() }

    /** 从任意 content uri 复制并压缩为私有文件，返回文件名（不含路径） */
    fun importFromUri(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        val sample = computeSample(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val name = "img_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val out = File(imagesDir(context), name)
        out.outputStream().use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        bitmap.recycle()
        name
    }.getOrNull()

    fun fileFor(context: Context, fileName: String): File = File(imagesDir(context), fileName)

    fun deleteFile(context: Context, fileName: String) {
        runCatching { File(imagesDir(context), fileName).delete() }
    }

    fun newCameraTarget(context: Context): File {
        val name = "cam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        return File(imagesDir(context), name)
    }

    /** 相机输出可能很大，导入时再压一遍 */
    fun importCaptured(context: Context, file: File): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = computeSample(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap != null) {
            file.outputStream().use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            bitmap.recycle()
        }
        file.name
    }.getOrNull()

    /** 供速记浮窗等非 Compose 场景使用：把 bitmap 存为新图片文件 */
    fun saveBitmap(context: Context, bitmap: Bitmap): String? = runCatching {
        val name = "quick_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val out = File(imagesDir(context), name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        name
    }.getOrNull()

    private fun computeSample(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DIMENSION * 2) sample *= 2
        return sample
    }
}
