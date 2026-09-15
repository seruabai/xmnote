package com.purenote.local.sync

import com.purenote.local.backup.BackupIo
import com.purenote.local.backup.BackupJson
import com.purenote.local.backup.BackupManifest
import com.purenote.local.backup.BackupVerifier
import com.purenote.local.backup.ManifestEntry
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用：**真的**造一个备份包并按清单校验读回来的那份。
 *
 * 刻意不用 mock：H 阶段最需要证明的正是"上传之后读回来的确实是同一份完整包"。
 * 用假数据构造这个证明，等于什么都没证明。
 */
object TestBackupPackage {

    data class Spec(
        val backupId: String,
        val libraryId: String = "lib-1",
        val noteCount: Int = 3,
        val noteBodies: List<String> = listOf("第一条", "第二条", "第三条"),
        val attachments: Map<String, ByteArray> = mapOf("img_1.jpg" to ByteArray(64) { it.toByte() }),
        val complete: Boolean = true,
        val includeManifest: Boolean = true,
    )

    fun write(out: java.io.OutputStream, spec: Spec) {
        val entries = mutableListOf<ManifestEntry>()
        ZipOutputStream(out).use { zip ->
            val json = buildString {
                append("{\"schema\":2,\"notes\":[")
                spec.noteBodies.forEachIndexed { i, body ->
                    if (i > 0) append(',')
                    append("{\"uuid\":\"n").append(i).append("\",\"body\":\"").append(body).append("\"}")
                }
                append("]}")
            }.toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(BackupIo.ENTRY_JSON))
            zip.write(json)
            zip.closeEntry()
            entries += ManifestEntry(BackupIo.ENTRY_JSON, json.size.toLong(), BackupVerifier.sha256Of(json))

            spec.attachments.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(BackupIo.ATTACHMENT_DIR + name))
                zip.write(bytes)
                zip.closeEntry()
                entries += ManifestEntry(
                    BackupIo.ATTACHMENT_DIR + name,
                    bytes.size.toLong(),
                    BackupVerifier.sha256Of(bytes),
                )
            }

            if (spec.includeManifest) {
                val manifest = BackupManifest(
                    formatVersion = BackupManifest.CURRENT_FORMAT,
                    appVersion = "1.2.20",
                    sourceSchema = 9,
                    libraryId = spec.libraryId,
                    backupId = spec.backupId,
                    createdAt = 1_800_000_000_000L,
                    entries = entries.toList(),
                    counts = com.purenote.local.backup.ManifestCounts(
                        notes = spec.noteCount,
                        todos = 0,
                        folders = 0,
                        attachments = spec.attachments.size,
                    ),
                    complete = spec.complete,
                )
                val manifestBytes = BackupJson.encodeManifest(manifest).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(BackupIo.ENTRY_MANIFEST))
                zip.write(manifestBytes)
                zip.closeEntry()
            }
        }
    }

    fun bytes(spec: Spec): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        write(out, spec)
        return out.toByteArray()
    }

    /** 与生产云同步路径同一套判断：读回整包 -> 校验清单 -> 取出 backupId 与计数。 */
    fun inspect(input: InputStream): CloudBackupOwner.BackupInspection {
        val read = BackupIo.inspectEntries(input)
        val manifest = BackupJson.decodeManifest(
            read.manifestJson ?: throw IllegalArgumentException("包没有清单"),
        )
        when (val outcome = BackupVerifier.verify(manifest, read.digests)) {
            is BackupVerifier.Outcome.Ok -> Unit
            is BackupVerifier.Outcome.Failed ->
                throw IllegalArgumentException("清单校验失败：" + outcome.reasons.joinToString("；"))
        }
        return CloudBackupOwner.BackupInspection(
            backupId = manifest.backupId,
            libraryId = manifest.libraryId,
            noteCount = manifest.counts.notes,
            attachmentCount = manifest.counts.attachments,
            complete = manifest.complete,
        )
    }
}
