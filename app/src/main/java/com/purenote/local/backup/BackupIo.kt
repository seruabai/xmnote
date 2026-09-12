package com.purenote.local.backup

import android.content.Context
import com.purenote.local.core.ImageStore
import com.purenote.local.data.NotesDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包（zip）的读写：`backup.json` + `attachments/<文件名>`。
 *
 * 安全与可靠性要求（导入是"写用户数据"的高风险操作）：
 * 1. **原子产出**：先写 `.tmp`，成功后 rename。中途失败不会留下半个文件冒充成功。
 * 2. **防 Zip Slip**：附件名一律取 basename，任何带路径分隔符的条目都拒绝，
 *    绝不允许备份包往目录外写文件。
 * 3. **附件同名冲突不覆盖已有文件**：导入时若目标已存在同名文件且大小非零则复用，
 *    避免把用户现有图片覆盖成零字节。
 * 4. 所有 IO 在 [Dispatchers.IO]。
 */
class BackupIo(private val context: Context) {

    companion object {
        const val ENTRY_JSON = "backup.json"
        const val ATTACHMENT_DIR = "attachments/"
        const val EXTENSION = "purenote.zip"
    }

    /** 导出到指定输出文件；包含 images/ 目录下被笔记引用的附件。 */
    suspend fun export(target: File, db: NotesDb, appVersion: String): ExportResult =
        withContext(Dispatchers.IO) {
            val backup = BackupCodec.export(db, appVersion, System.currentTimeMillis())
            val referenced = BackupCodec.referencedAttachments(backup)

            val tmp = File(target.parentFile, target.name + ".tmp")
            var attachmentCount = 0
            var attachmentBytes = 0L
            var missing = 0

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY_JSON))
                zip.write(BackupJson.encode(backup).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val dir = ImageStore.imagesDir(context)
                backup.notes.flatMap { it.images }.distinct().forEach { name ->
                    val safe = sanitizeEntryName(name) ?: return@forEach
                    val src = File(dir, safe)
                    if (!src.isFile) {
                        missing++
                        return@forEach
                    }
                    zip.putNextEntry(ZipEntry(ATTACHMENT_DIR + safe))
                    src.inputStream().use { it.copyTo(zip, DEFAULT_BUFFER_SIZE) }
                    zip.closeEntry()
                    attachmentCount++
                    attachmentBytes += src.length()
                }
            }

            // 原子替换：只有 tmp 完整写好了才动目标文件
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            ExportResult(
                file = target,
                noteCount = backup.notes.size,
                todoCount = backup.todos.size,
                folderCount = backup.folders.size,
                attachmentCount = attachmentCount,
                attachmentBytes = attachmentBytes,
                missingAttachments = missing,
                referencedAttachments = referenced.size,
            )
        }

    /** 只解析备份内容，不改数据库。用于导入前预览"会新增/更新多少"。 */
    suspend fun readBackup(source: InputStream): BackupFile = withContext(Dispatchers.IO) {
        val (json, _) = readEntries(source)
        BackupJson.decode(json ?: throw BackupFormatException("备份包里没有 $ENTRY_JSON"))
    }

    /**
     * 导入。顺序：先解析 JSON → 规划 → 应用数据库（事务）→ 再还原附件。
     * 附件失败不回滚数据库（内容已在，仅缺图，比整个导入失败更好），会记入 warnings。
     */
    suspend fun import(source: InputStream, db: NotesDb): ImportResult = withContext(Dispatchers.IO) {
        val (json, attachments) = readEntries(source)
        val backup = BackupJson.decode(json ?: throw BackupFormatException("备份包里没有 $ENTRY_JSON"))
        val snapshot = BackupCodec.snapshot(db)
        val plan = BackupMerger.plan(backup, snapshot)
        val applied = BackupCodec.apply(db, plan, snapshot)

        // 还原附件：已存在且非空则跳过，不覆盖用户现有文件
        val dir = ImageStore.imagesDir(context)
        var restored = 0
        var skippedExisting = 0
        val attachWarnings = mutableListOf<String>()
        attachments.forEach { (name, bytes) ->
            val dst = File(dir, name)
            if (dst.isFile && dst.length() > 0) {
                skippedExisting++
            } else {
                runCatching {
                    FileOutputStream(dst).use { it.write(bytes) }
                    restored++
                }.onFailure { attachWarnings += "附件 $name 写入失败：${it.message}" }
            }
        }

        ImportResult(
            inserted = applied.inserted,
            updated = applied.updated,
            skipped = applied.skipped,
            attachmentsRestored = restored,
            attachmentsSkippedExisting = skippedExisting,
            warnings = applied.warnings + attachWarnings,
        )
    }

    /**
     * 解出 `backup.json` 文本与附件表（附件名已做 Zip Slip 清洗）。
     * 非法条目直接拒绝整个导入，不静默跳过。
     */
    private fun readEntries(source: InputStream): Pair<String?, Map<String, ByteArray>> {
        var json: String? = null
        val attachments = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == ENTRY_JSON -> json = zip.readBytes().toString(Charsets.UTF_8)
                    name.startsWith(ATTACHMENT_DIR) && !entry.isDirectory -> {
                        val base = sanitizeEntryName(name.removePrefix(ATTACHMENT_DIR))
                            ?: throw BackupFormatException("备份包含非法附件路径：$name")
                        attachments[base] = zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
        return json to attachments
    }

    /**
     * 只取纯文件名，拒绝任何目录穿越。
     * `..`、`/`、`\`、绝对路径、空名一律视为非法。
     */
    private fun sanitizeEntryName(raw: String): String? = BackupPaths.sanitizeEntryName(raw)

    data class ExportResult(
        val file: File,
        val noteCount: Int,
        val todoCount: Int,
        val folderCount: Int,
        val attachmentCount: Int,
        val attachmentBytes: Long,
        val missingAttachments: Int,
        val referencedAttachments: Int,
    ) {
        val summary: String
            get() = "笔记 $noteCount 条、待办 $todoCount 条、分类 $folderCount 个、附件 $attachmentCount 个"
    }

    data class ImportResult(
        val inserted: Int,
        val updated: Int,
        val skipped: Int,
        val attachmentsRestored: Int,
        val attachmentsSkippedExisting: Int,
        val warnings: List<String>,
    ) {
        val summary: String
            get() = "新增 $inserted、更新 $updated、跳过 $skipped" +
                if (attachmentsRestored > 0) "，附件还原 $attachmentsRestored 个" else ""
    }
}
