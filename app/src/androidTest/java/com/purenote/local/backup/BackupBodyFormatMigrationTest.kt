package com.purenote.local.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.data.NotesDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 规范 §5.2 / §13.1：恢复旧备份时正文格式必须被迁移。
 *
 * 这是一个真实存在过的缺口：库内迁移只挂在 `onUpgrade(oldVersion < 8)` 上，
 * 而全新安装走 onCreate 不经过 onUpgrade；备份格式号当时也没有 +1。
 * 结果是把 v1.2.20 的备份恢复到新机，旧标记会逐字节落库并永久残留。
 */
@RunWith(AndroidJUnit4::class)
class BackupBodyFormatMigrationTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: NotesDb

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        ctx.deleteDatabase(DB)
    }

    private fun bodyOf(noteId: Long): String =
        db.readableDatabase.rawQuery("SELECT body FROM notes WHERE id = ?", arrayOf(noteId.toString()))
            .use { c -> c.moveToFirst(); c.getString(0) }

    private fun formatOf(noteId: Long): Int =
        db.readableDatabase.rawQuery("SELECT body_format_version FROM notes WHERE id = ?", arrayOf(noteId.toString()))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun schemaOneBackupWithLegacyBodyIsMigratedOnImport() {
        ctx.deleteDatabase(DB)
        db = NotesDb(ctx, DB)
        val local = BackupCodec.snapshot(db)

        // schema=1 的旧备份：正文是 PUA 标题 + ☐ 勾选 + [img:] 图片行
        val legacyBody = "\uE201\u6b22\u8fce\n\u2610 \u4e70\u725b\u5976\n[img:old.jpg]"
        val backup = BackupFile(
            schema = 1,
            notes = listOf(
                NoteDto(
                    uuid = "legacy-1",
                    kind = 0,
                    title = "",
                    body = legacyBody,
                    bodyFormatVersion = 1,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                ),
            ),
        )

        val plan = BackupMerger.plan(backup, local)
        BackupCodec.apply(db, plan, local)

        val id = db.readableDatabase.rawQuery("SELECT id FROM notes WHERE uuid = 'legacy-1'", null)
            .use { c -> c.moveToFirst(); c.getLong(0) }

        val stored = bodyOf(id)
        assertTrue("PUA 标题应转成 Markdown，实际=$stored", stored.contains("# "))
        assertTrue("☐ 应转成任务语法，实际=$stored", stored.contains("- [ ] "))
        assertTrue("[img:] 应转成 Markdown 图片，实际=$stored", stored.contains("![](old.jpg)"))
        assertTrue("不得残留 PUA 字符", !stored.contains('\uE201'))
        assertEquals("正文格式版本应标为 Markdown", NotesDb.BODY_FORMAT_MARKDOWN, formatOf(id))
    }

    @Test
    fun alreadyMarkdownBackupIsStoredVerbatim() {
        ctx.deleteDatabase(DB)
        db = NotesDb(ctx, DB)
        val local = BackupCodec.snapshot(db)

        val markdown = "# 标题\n- [ ] 任务\n![](new.jpg)"
        val backup = BackupFile(
            schema = 2,
            notes = listOf(
                NoteDto(
                    uuid = "md-1", kind = 0, title = "", body = markdown,
                    bodyFormatVersion = NotesDb.BODY_FORMAT_MARKDOWN,
                    createdAt = 1000L, updatedAt = 1000L,
                ),
            ),
        )
        BackupCodec.apply(db, BackupMerger.plan(backup, local), local)

        val id = db.readableDatabase.rawQuery("SELECT id FROM notes WHERE uuid = 'md-1'", null)
            .use { c -> c.moveToFirst(); c.getLong(0) }
        assertEquals("已是 Markdown 的正文必须逐字保留", markdown, bodyOf(id))
    }

    @Test
    fun exportRecordsTheBodyFormatVersion() {
        ctx.deleteDatabase(DB)
        db = NotesDb(ctx, DB)
        val now = 2000L
        db.insertNote(com.purenote.local.data.NoteKind.TEXT, "t", "# h", "", 0, null, now)

        val exported = BackupCodec.export(db, appVersion = "test", now = now)
        assertEquals("导出的备份格式号应为 2", 2, exported.schema)
        val note = exported.notes.single()
        assertEquals(NotesDb.BODY_FORMAT_MARKDOWN, note.bodyFormatVersion)
        assertEquals("# h", note.body)
    }

    private companion object {
        const val DB = "backup-bodyfmt-test.db"
    }
}
