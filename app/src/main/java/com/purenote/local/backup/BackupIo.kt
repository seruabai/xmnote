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
 * 备份包（zip）的读写：`backup.json` + `attachments/<文件名>` + `manifest.json`。
 *
 * 安全与可靠性要求（导入是"写用户数据"的高风险操作）：
 * 1. **原子产出**：先写 `.tmp`，成功后 rename。中途失败不会留下半个文件冒充成功。
 * 2. **防 Zip Slip**：附件名一律取 basename，任何带路径分隔符的条目都拒绝。
 * 3. **完整性校验**（规范 §11.1）：清单里逐项记录 size + SHA-256，导入前必须全对；
 *    截断/改字节/缺附件/重复条目一律**拒绝整个导入**，绝不用坏包替换有效备份。
 * 4. **附件同名冲突不覆盖已有文件**：目标已存在且非空则复用。
 * 5. 所有 IO 在 [Dispatchers.IO]。
 *
 * 兼容性：`manifest.json` 是格式 v2 新增的。读没有清单的旧包时不拒绝，
 * 但在 [ImportResult.warnings] 里如实说明"未经完整性校验"。
 */
class BackupIo(private val context: Context?) {

    companion object {
        /**
         * 只解包不校验清单（校验由 [BackupVerifier] 负责）。
         *
         * 放在伴生对象里给 JVM 测试用：测试需要"读回一份假包并核对它还是原来那份"，
         * 但不需要 Context。生产路径仍走实例方法，行为是同一份实现。
         */
        internal fun inspectEntries(source: InputStream): ReadResult =
            BackupIo(null).readEntries(source)

        const val ENTRY_JSON = "backup.json"
        const val ENTRY_MANIFEST = "manifest.json"
        const val ATTACHMENT_DIR = "attachments/"
        const val EXTENSION = "purenote.zip"
    }

    /** 导出到指定输出文件；包含 images/ 目录下被笔记引用的附件。 */
    suspend fun export(target: File, db: NotesDb, appVersion: String): ExportResult =
        withContext(Dispatchers.IO) {
            // 规范 §11.2 第 3 步：所有业务表必须在**同一个事务**里读出，
            // 否则"读 notes 之后、读 todos 之前"发生的写入会让备份变成新旧混合的状态。
            // NotesDb 默认未启用 WAL，readableDatabase 与 writableDatabase 是同一条连接，
            // 因此在写事务里读取即可获得一致快照（若将来启用 WAL，必须改为传入同一个 database）。
            val backup = db.inTransaction {
                BackupCodec.export(db, appVersion, System.currentTimeMillis())
            }
            val referenced = BackupCodec.referencedAttachments(backup)

            val tmp = File(target.parentFile, target.name + ".tmp")
            var attachmentCount = 0
            var attachmentBytes = 0L
            var missing = 0
            val entries = mutableListOf<ManifestEntry>()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                val jsonBytes = BackupJson.encode(backup).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(ENTRY_JSON))
                zip.write(jsonBytes)
                zip.closeEntry()
                entries += ManifestEntry(ENTRY_JSON, jsonBytes.size.toLong(), BackupVerifier.sha256Of(jsonBytes))

                val dir = ImageStore.imagesDir(requireContext())
                backup.notes.flatMap { it.images }.distinct().forEach { name ->
                    val safe = sanitizeEntryName(name) ?: return@forEach
                    val src = File(dir, safe)
                    if (!src.isFile) {
                        missing++
                        return@forEach
                    }
                    val bytes = src.readBytes()
                    zip.putNextEntry(ZipEntry(ATTACHMENT_DIR + safe))
                    zip.write(bytes)
                    zip.closeEntry()
                    entries += ManifestEntry(
                        ATTACHMENT_DIR + safe,
                        bytes.size.toLong(),
                        BackupVerifier.sha256Of(bytes),
                    )
                    attachmentCount++
                    attachmentBytes += bytes.size
                }

                // 清单最后写：它必须包含前面所有条目的校验值
                val manifest = BackupManifest(
                    formatVersion = BackupManifest.CURRENT_FORMAT,
                    appVersion = appVersion,
                    sourceSchema = NotesDb.DB_VERSION,
                    libraryId = BackupCodec.libraryId(db),
                    backupId = java.util.UUID.randomUUID().toString().replace("-", ""),
                    createdAt = System.currentTimeMillis(),
                    entries = entries.toList(),
                    counts = ManifestCounts(
                        notes = backup.notes.size,
                        todos = backup.todos.size,
                        folders = backup.folders.size,
                        attachments = attachmentCount,
                    ),
                    // 有附件缺失时如实标注：这份包可以用于抢救，但不能冒充最后一份完整备份
                    complete = missing == 0,
                )
                val manifestBytes = BackupJson.encodeManifest(manifest).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zip.write(manifestBytes)
                zip.closeEntry()
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
                verified = true,
            )
        }

    /** 只解析备份内容，不改数据库。用于导入前预览"会新增/更新多少"。 */
    suspend fun readBackup(source: InputStream): BackupFile = withContext(Dispatchers.IO) {
        val read = readEntries(source)
        BackupJson.decode(read.json ?: throw BackupFormatException("备份包里没有 $ENTRY_JSON"))
    }

    /**
     * 读回一个**已存在**的备份包的清单（云同步上传前/上传后校验用）。
     *
     * 与 [readBackup] 的区别：这里额外返回 manifest，并且**校验清单**。
     * 云同步的场景是"已经有一份刚生成的包，要确认它是完整、可恢复的再传上去"，
     * 而不是"解析内容准备导入"。
     *
     * @throws BackupFormatException 包里没有清单，或清单与内容对不上
     */
    suspend fun inspect(source: InputStream): InspectResult = withContext(Dispatchers.IO) {
        val read = readEntries(source)
        read.json ?: throw BackupFormatException("备份包里没有 $ENTRY_JSON")
        val rawManifest = read.manifestJson
            ?: throw BackupFormatException("备份包没有完整性清单，不能作为云同步的源")
        val manifest = BackupJson.decodeManifest(rawManifest)
        when (val outcome = BackupVerifier.verify(manifest, read.digests)) {
            is BackupVerifier.Outcome.Ok -> Unit
            is BackupVerifier.Outcome.Failed ->
                throw BackupFormatException(
                    "备份完整性校验未通过：\n" + outcome.reasons.joinToString("\n"),
                )
        }
        InspectResult(
            manifest = manifest,
            attachmentCount = read.attachments.size,
            attachmentBytes = read.attachments.values.sumOf { it.size.toLong() },
        )
    }

    /** [inspect] 的结果：清单（含 backupId / libraryId / 计数）+ 实际附件统计。 */
    data class InspectResult(
        val manifest: BackupManifest,
        val attachmentCount: Int,
        val attachmentBytes: Long,
    )

    /**
     * 导入。顺序：读取并解析 -> **先校验清单** -> 规划 -> 应用数据库（事务）-> 再还原附件。
     * 校验不通过直接抛 [BackupFormatException]，不做任何写入。
     */
    suspend fun import(source: InputStream, db: NotesDb): ImportResult = withContext(Dispatchers.IO) {
        val read = readEntries(source)
        val backup = BackupJson.decode(read.json ?: throw BackupFormatException("备份包里没有 $ENTRY_JSON"))

        val warnings = mutableListOf<String>()
        val manifest = read.manifestJson?.let { BackupJson.decodeManifest(it) }
        if (manifest == null) {
            // 格式 v1 的旧包没有清单：不拒绝，但必须如实说明
            warnings += "这份备份没有完整性清单（旧格式），未做校验"
        } else {
            when (val outcome = BackupVerifier.verify(manifest, read.digests)) {
                is BackupVerifier.Outcome.Ok -> Unit
                is BackupVerifier.Outcome.Failed ->
                    throw BackupFormatException(
                        "备份完整性校验未通过，已拒绝导入：\n" + outcome.reasons.joinToString("\n"),
                    )
            }
            if (!manifest.complete) {
                warnings += "这份备份导出时就有附件缺失，属于不完整包"
            }
        }

        val snapshot = BackupCodec.snapshot(db)
        val plan = BackupMerger.plan(backup, snapshot)
        // 规范 §13.2：把来源库身份带进导入，用于建立跨库实体映射
        val applied = BackupCodec.apply(db, plan, snapshot, sourceLibraryId = manifest?.libraryId ?: "")

        val dir = ImageStore.imagesDir(requireContext())
        var restored = 0
        var skippedExisting = 0
        val attachWarnings = mutableListOf<String>()
        read.attachments.forEach { (name, bytes) ->
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
            warnings = warnings + applied.warnings + attachWarnings,
        )
    }

    internal class ReadResult(
        val json: String?,
        val manifestJson: String?,
        val attachments: Map<String, ByteArray>,
        val digests: List<EntryDigest>,
    )

    /**
     * 解出 backup.json / manifest.json / 附件表，并同步计算每个条目的 size 与 SHA-256。
     * 非法路径、重复条目一律拒绝整个导入，不静默跳过。
     */
    internal fun readEntries(source: InputStream): ReadResult {
        var json: String? = null
        var manifestJson: String? = null
        val attachments = mutableMapOf<String, ByteArray>()
        val digests = mutableListOf<EntryDigest>()
        val seen = mutableSetOf<String>()

        ZipInputStream(BufferedInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory) {
                    if (!seen.add(name)) {
                        throw BackupFormatException("备份包含重复条目：$name")
                    }
                    val bytes = zip.readBytes()
                    when {
                        name == ENTRY_JSON -> json = bytes.toString(Charsets.UTF_8)
                        name == ENTRY_MANIFEST -> manifestJson = bytes.toString(Charsets.UTF_8)
                        name.startsWith(ATTACHMENT_DIR) -> {
                            val base = sanitizeEntryName(name.removePrefix(ATTACHMENT_DIR))
                                ?: throw BackupFormatException("备份包含非法附件路径：$name")
                            attachments[base] = bytes
                            digests += EntryDigest(name, bytes.size.toLong(), BackupVerifier.sha256Of(bytes))
                        }
                        else -> throw BackupFormatException("备份包含未知条目：$name")
                    }
                    if (name == ENTRY_JSON) {
                        digests += EntryDigest(name, bytes.size.toLong(), BackupVerifier.sha256Of(bytes))
                    }
                }
                zip.closeEntry()
            }
        }
        return ReadResult(json, manifestJson, attachments, digests)
    }

    /**
     * 只取纯文件名，拒绝任何目录穿越。
     * `..`、`/`、`\`、绝对路径、空名一律视为非法。
     */
    /**
     * 只有真正碰附件目录的路径才需要 Context。
     * 显式抛错而不是 !!：将来有人把 null 传进生产路径时，错误信息要能说明原因。
     */
    private fun requireContext(): Context = context
        ?: error("BackupIo 需要 Context 才能读写附件目录（只有测试用的解包路径可以传 null）")

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
        /** 是否已生成完整性清单 */
        val verified: Boolean,
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
