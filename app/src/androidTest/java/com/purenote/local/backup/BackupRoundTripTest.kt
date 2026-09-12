package com.purenote.local.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.core.ImageStore
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NotesDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * 端到端往返验证：真实 SQLite + 真实 zip + 真实文件系统。
 *
 * 单测覆盖了合并规则，但覆盖不到事务、walk 附件、zip 读写、原子替换这些
 * "只有真机才暴露"的环节。这个测试直接模拟换机：导出 → 清空 → 导入 → 断言。
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private lateinit var context: Context
    private lateinit var db: NotesDb

    private val dbName = "test-backup-roundtrip.db"
    private val attachment = "test_rt_img.jpg"
    private val audio = "test_rt_aud.m4a"
    private lateinit var tmpZip: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        db = NotesDb(context, dbName)
        tmpZip = File(context.cacheDir, "test-backup-roundtrip.zip").also { it.delete() }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        tmpZip.delete()
        ImageStore.deleteFile(context, attachment)
        ImageStore.deleteFile(context, audio)
    }

    @Test
    fun exportThenRestore_keepsEverything() = runBlocking {
        // ---- 造数据：分类、普通笔记、带附件清单、父/子待办 ----
        val folderId = db.insertFolder("工作", 100L)
        val now = 1_700_000_000_000L
        db.insertNote(
            kind = NoteKind.TEXT, title = "普通笔记", encodedBody = "正文\n第二行",
            images = "", colorIndex = 2, folderId = folderId, now = now,
        )
        val checklistBody = ChecklistCodec.encode(
            listOf(ChecklistItem("买牛奶", true), ChecklistItem("写周报")),
        )
        db.insertNote(
            kind = NoteKind.CHECKLIST, title = "清单笔记", encodedBody = checklistBody,
            images = "$attachment\n$audio", colorIndex = 0, folderId = null, now = now + 1,
        )
        val parentId = db.insertTodo(null, "父待办", now, false, 0, 0, now)
        db.insertTodo(parentId, "子待办", null, false, 0, 1, now)

        // 附件真实落盘（导出要能打包到）
        ImageStore.imagesDir(context).let { dir ->
            File(dir, attachment).writeBytes(ByteArray(2048) { it.toByte() })
            File(dir, audio).writeBytes(ByteArray(1024) { (it * 2).toByte() })
        }

        // ---- 导出 ----
        val io = BackupIo(context)
        val result = io.export(tmpZip, db, appVersion = "1.2.20")
        assertTrue("zip 应存在且非空", tmpZip.isFile && tmpZip.length() > 0)
        assertEquals(2, result.noteCount)
        assertEquals(2, result.todoCount)
        assertEquals(1, result.folderCount)
        assertEquals(2, result.attachmentCount)
        assertEquals(0, result.missingAttachments)

        // zip 结构正确
        ZipFile(tmpZip).use { zf ->
            assertNotNull(zf.getEntry(BackupIo.ENTRY_JSON))
            assertNotNull(zf.getEntry(BackupIo.ATTACHMENT_DIR + attachment))
            assertNotNull(zf.getEntry(BackupIo.ATTACHMENT_DIR + audio))
        }

        // 记下导出时的 uuid，导入后要一致
        val parsed = BackupJson.decode(
            ZipFile(tmpZip).use { it.getInputStream(it.getEntry(BackupIo.ENTRY_JSON)).readBytes().toString(Charsets.UTF_8) },
        )
        val textUuid = parsed.notes.first { it.title == "普通笔记" }.uuid
        assertTrue("uuid 不应为空", textUuid.isNotBlank())
        assertTrue("清单笔记应带上 items", parsed.notes.first { it.title == "清单笔记" }.items.size == 2)
        val textFolderUuid = parsed.notes.first { it.title == "普通笔记" }.folderUuid
        assertNotNull("笔记应带上分类引用", textFolderUuid)
        assertEquals("工作", parsed.folders.first { it.uuid == textFolderUuid }.name)

        // ---- 模拟换机：清空整个库，并删掉附件文件 ----
        db.writableDatabase.delete("notes", null, null)
        db.writableDatabase.delete("todos", null, null)
        db.writableDatabase.delete("folders", null, null)
        ImageStore.deleteFile(context, attachment)
        ImageStore.deleteFile(context, audio)
        db.writableDatabase.execSQL("DELETE FROM sqlite_sequence")

        // ---- 导入 ----
        val imported = FileInputStream(tmpZip).use { io.import(it, db) }
        // inserted 统计的是笔记 + 待办：2 条笔记 + 2 条待办 = 4
        assertEquals(4, imported.inserted)
        assertEquals(0, imported.updated)
        assertEquals(0, imported.skipped)
        assertEquals(2, imported.attachmentsRestored)
        assertTrue(imported.warnings.isEmpty())

        // ---- 断言数据完整 ----
        val notes = readNotes()
        assertEquals(2, notes.size)
        val restoredText = notes.first { it.first == "普通笔记" }
        assertEquals(textUuid, restoredText.second)          // uuid 保真
        assertEquals("工作", db.readableDatabase.rawQuery(
            "SELECT f.name FROM notes n JOIN folders f ON n.folder_id = f.id WHERE n.title = ?",
            arrayOf("普通笔记"),
        ).use { if (it.moveToFirst()) it.getString(0) else null })  // 分类按名字重建并正确关联

        // 父/子待办关系恢复
        db.readableDatabase.rawQuery(
            "SELECT c.title FROM todos c JOIN todos p ON c.parent_id = p.id WHERE p.title = '父待办'",
            null,
        ).use { c ->
            assertTrue("子待办应挂在父待办下", c.moveToFirst())
            assertEquals("子待办", c.getString(0))
        }

        // 附件文件真的回来了
        assertTrue("图片附件应还原", ImageStore.fileFor(context, attachment).let { it.isFile && it.length() == 2048L })
        assertTrue("录音附件应还原", ImageStore.fileFor(context, audio).let { it.isFile && it.length() == 1024L })

        // ---- 幂等：再导入一次不应产生任何变更 ----
        val second = FileInputStream(tmpZip).use { io.import(it, db) }
        assertEquals("重复导入不应新增", 0, second.inserted)
        assertEquals("重复导入不应更新", 0, second.updated)
        assertEquals("重复导入应全部跳过（2 笔记 + 2 待办）", 4, second.skipped)
        assertEquals(2, readNotes().size)
    }

    private fun readNotes(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        db.readableDatabase.rawQuery("SELECT title, uuid FROM notes", null).use { c ->
            while (c.moveToNext()) list += c.getString(0) to c.getString(1)
        }
        return list
    }

    /**
     * 父待办只存在于本机、不在备份里时，备份中的子项必须挂到本机父项下，
     * 不能因为"备份里没这个父"就被误提升为顶层（内容不丢，但层级被破坏）。
     */
    @Test
    fun childWhoseParentExistsOnlyLocally_keepsHierarchy() = runBlocking {
        val now = 1_700_000_000_000L
        val parentId = db.insertTodo(null, "本机父待办", null, false, 0, 0, now)
        val parentUuid = db.readableDatabase.rawQuery(
            "SELECT uuid FROM todos WHERE id = ?", arrayOf(parentId.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else "" }

        // 构造只含子项的备份（父项故意不进备份）
        val backup = BackupFile(
            notes = emptyList(),
            todos = listOf(
                TodoDto(uuid = "child-only", parentUuid = parentUuid, title = "备份里的子待办", updatedAt = now + 1),
            ),
        )
        val tmp = File(context.cacheDir, "child-only.purenote.zip")
        java.util.zip.ZipOutputStream(tmp.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(BackupIo.ENTRY_JSON))
            z.write(BackupJson.encode(backup).toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }

        val io = BackupIo(context)
        val result = FileInputStream(tmp).use { io.import(it, db) }
        assertEquals(1, result.inserted)

        // 子项应挂在"本机父待办"下，而不是变成顶层
        db.readableDatabase.rawQuery(
            "SELECT c.parent_id, p.id FROM todos c JOIN todos p ON c.parent_id = p.id WHERE c.title = ?",
            arrayOf("备份里的子待办"),
        ).use { c ->
            assertTrue("应保留父子关系", c.moveToFirst())
            assertEquals(parentId, c.getLong(0))
        }
        // 确认没有多出一个顶层副本
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM todos WHERE parent_id IS NULL", null,
        ).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        tmp.delete()
        Unit
    }
}
