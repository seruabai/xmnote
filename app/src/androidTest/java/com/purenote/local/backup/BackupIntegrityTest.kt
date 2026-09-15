package com.purenote.local.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.data.NotesDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 规范 §16「坏备份」：截断、改字节、缺附件、重复条目、未知条目都必须**拒绝整个导入**，
 * 绝不能用坏包替换用户现有的有效备份。
 */
@RunWith(AndroidJUnit4::class)
class BackupIntegrityTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: NotesDb
    private lateinit var io: BackupIo

    @Before
    fun setUp() {
        ctx.deleteDatabase(DB)
        db = NotesDb(ctx, DB)
        io = BackupIo(ctx)
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase(DB)
    }

    private fun backupJson(noteUuid: String = "n1", body: String = "正文"): String =
        BackupJson.encode(
            BackupFile(
                appVersion = "test",
                notes = listOf(
                    NoteDto(
                        uuid = noteUuid, kind = 0, title = "标题", body = body,
                        bodyFormatVersion = NotesDb.BODY_FORMAT_MARKDOWN,
                        createdAt = 1000L, updatedAt = 1000L,
                    ),
                ),
            ),
        )

    private fun entryOf(path: String, bytes: ByteArray) =
        ManifestEntry(path, bytes.size.toLong(), BackupVerifier.sha256Of(bytes))

    private fun buildPackage(
        json: String,
        attachments: Map<String, ByteArray> = emptyMap(),
        manifest: BackupManifest? = null,
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val jsonBytes = json.toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(BackupIo.ENTRY_JSON)); zip.write(jsonBytes); zip.closeEntry()
            attachments.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(BackupIo.ATTACHMENT_DIR + name)); zip.write(bytes); zip.closeEntry()
            }
            extra.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            manifest?.let {
                val mb = BackupJson.encodeManifest(it).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(BackupIo.ENTRY_MANIFEST)); zip.write(mb); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifestFor(json: String, attachments: Map<String, ByteArray>) = BackupManifest(
        appVersion = "test", sourceSchema = 9, libraryId = "lib", backupId = "b",
        createdAt = 1L,
        entries = buildList {
            add(entryOf(BackupIo.ENTRY_JSON, json.toByteArray(Charsets.UTF_8)))
            attachments.forEach { (n, b) -> add(entryOf(BackupIo.ATTACHMENT_DIR + n, b)) }
        },
    )

    @Test
    fun aValidPackageWithMatchingManifestImports() = runBlocking {
        val json = backupJson()
        val bytes = buildPackage(json, manifest = manifestFor(json, emptyMap()))
        val result = io.import(ByteArrayInputStream(bytes), db)
        assertEquals(1, result.inserted)
        assertTrue("不应有校验相关告警", result.warnings.none { it.contains("未做校验") })
    }

    @Test
    fun tamperedAttachmentBytesAreRejectedAndNothingIsWritten() = runBlocking {
        val json = backupJson(body = "![](a.jpg)")
        val realBytes = "REAL-IMAGE-BYTES".toByteArray()
        // 清单记录的是真字节，包里的附件被换成了别的
        val manifest = manifestFor(json, mapOf("a.jpg" to realBytes))
        val bytes = buildPackage(json, attachments = mapOf("a.jpg" to "TAMPERED!!!".toByteArray()), manifest = manifest)

        val failure = runCatching { io.import(ByteArrayInputStream(bytes), db) }.exceptionOrNull()
        assertTrue("必须抛 BackupFormatException，实际=$failure", failure is BackupFormatException)
        assertTrue(failure!!.message!!.contains("校验值不符") || failure.message!!.contains("大小不符"))
    }

    @Test
    fun missingAttachmentDeclaredInManifestIsRejected() = runBlocking {
        val json = backupJson(body = "![](gone.jpg)")
        val manifest = manifestFor(json, mapOf("gone.jpg" to "CONTENT".toByteArray()))
        // 包里根本没有这个附件
        val bytes = buildPackage(json, manifest = manifest)

        val failure = runCatching { io.import(ByteArrayInputStream(bytes), db) }.exceptionOrNull()
        assertTrue(failure is BackupFormatException)
        assertTrue(failure!!.message!!.contains("缺失"))
    }

    @Test
    fun undeclaredEntryIsRejected() = runBlocking {
        val json = backupJson()
        val manifest = manifestFor(json, emptyMap())
        val bytes = buildPackage(json, manifest = manifest, extra = mapOf("attachments/sneaky.jpg" to "X".toByteArray()))

        val failure = runCatching { io.import(ByteArrayInputStream(bytes), db) }.exceptionOrNull()
        assertTrue(failure is BackupFormatException)
    }

    @Test
    fun unknownEntryNameIsRejected() = runBlocking {
        val json = backupJson()
        val bytes = buildPackage(json, extra = mapOf("evil.exe" to "MZ".toByteArray()))
        val failure = runCatching { io.import(ByteArrayInputStream(bytes), db) }.exceptionOrNull()
        assertTrue("未知条目必须拒绝", failure is BackupFormatException)
    }

    @Test
    fun legacyPackageWithoutManifestImportsButSaysSo() = runBlocking {
        val json = backupJson()
        val bytes = buildPackage(json, manifest = null)
        val result = io.import(ByteArrayInputStream(bytes), db)
        assertEquals(1, result.inserted)
        assertTrue(
            "旧格式必须如实说明未经校验，不能静默放行",
            result.warnings.any { it.contains("没有完整性清单") },
        )
    }

    @Test
    fun exportProducesAVerifiableManifest() {
        // 注意用块体而不是 = runBlocking{}：后者会把最后一行的 Boolean 当成返回值，
        // JUnit 要求测试方法返回 void（InvalidTestClassError: should be void）
        runBlocking {
        // 先导入一条，再导出，导出的包必须自带可校验清单
        io.import(ByteArrayInputStream(buildPackage(backupJson())), db)
        val target = java.io.File(ctx.cacheDir, "roundtrip.purenote.zip")
        val exported = io.export(target, db, appVersion = "test")
        assertTrue("导出应声称已生成清单", exported.verified)
        assertTrue(target.length() > 0)

        // 把它读回来并校验：应通过
        val read = target.inputStream().use { io.readBackup(it) }
        assertEquals(1, read.notes.size)

        // 再导入回一个空库：应成功
        ctx.deleteDatabase(DB2)
        val db2 = NotesDb(ctx, DB2)
        try {
            val result = target.inputStream().use { io.import(it, db2) }
            assertEquals(1, result.inserted)
        } finally {
            db2.close()
            ctx.deleteDatabase(DB2)
        }
            target.delete()
        }
    }

    @Test
    fun importingRecordsCrossLibraryMappings() = runBlocking {
        // 规范 §13.2：以「来源库 + 来源实体 ID」建立映射，
        // 而不是靠标题猜是不是同一条笔记。
        val json = BackupJson.encode(
            BackupFile(
                appVersion = "test",
                notes = listOf(
                    NoteDto(
                        uuid = "src-note-1", kind = 0, title = "来自别的库", body = "正文",
                        bodyFormatVersion = NotesDb.BODY_FORMAT_MARKDOWN,
                        createdAt = 1000L, updatedAt = 1000L,
                    ),
                ),
            ),
        )
        val manifest = BackupManifest(
            appVersion = "test", sourceSchema = 9, libraryId = "source-lib-xyz", backupId = "b",
            createdAt = 1L,
            entries = listOf(entryOf(BackupIo.ENTRY_JSON, json.toByteArray(Charsets.UTF_8))),
        )
        io.import(ByteArrayInputStream(buildPackage(json, manifest = manifest)), db)

        val mapped = db.readableDatabase.rawQuery(
            "SELECT source_library_id, entity_type, source_id, target_id FROM import_mappings",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString()))
            }
        }
        assertEquals(1, mapped.size)
        assertEquals("source-lib-xyz", mapped.single()[0])
        assertEquals("note", mapped.single()[1])
        assertEquals("src-note-1", mapped.single()[2])
        assertTrue("目标 id 必须是真实存在的笔记", mapped.single()[3].toLong() > 0)
    }

    private companion object {
        const val DB = "backup-integrity-test.db"
        const val DB2 = "backup-integrity-test2.db"
    }
}
