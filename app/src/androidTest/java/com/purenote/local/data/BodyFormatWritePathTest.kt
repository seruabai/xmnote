package com.purenote.local.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.core.LegacyBody
import com.purenote.local.core.RichDocCodec
import com.purenote.local.core.checklistProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 写入路径的设备级回归：无论从哪条路径写，库内必须恒为 v3 块文档，
 * 且读回来与写进去的内容逐字一致。
 *
 * 这是"当新程序做"之后的基线：不再考虑旧版本兼容，但**绝不能写坏**。
 */
@RunWith(AndroidJUnit4::class)
class BodyFormatWritePathTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "body-format-write.db"

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    /** 直接读磁盘上的原始行，绕开一切封装——这正是本用例要证明的东西 */
    private fun rawRow(): Triple<Int, String, Int> {
        val path = ctx.getDatabasePath(dbName).absolutePath
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        db.rawQuery("SELECT kind, body, body_format_version FROM notes ORDER BY id LIMIT 1", null).use { c ->
            assertTrue("应能读到一条笔记", c.moveToFirst())
            return Triple(c.getInt(0), c.getString(1), c.getInt(2))
        }
    }

    @Test
    fun textNoteIsStoredAsV3BlockDocumentAndReadBackIdentically() = runBlocking {
        val markup = "# 标题\n- [ ] 任务\n![](img_1.jpg)\n> 引用"
        val repo = NoteRepository(ctx, dbName)
        val id = repo.createNote(
            kind = NoteKind.TEXT,
            title = "标题",
            body = markup,
            items = emptyList(),
            images = listOf("img_1.jpg"),
            colorIndex = 0,
            folderId = null,
        )

        val (kind, body, version) = rawRow()
        assertEquals("kind 应为文本笔记", 0, kind)
        assertEquals("库内必须标记为 v3", 3, version)
        assertTrue("库内必须是块文档 JSON，而不是标记文本", RichDocCodec.looksLikeBlockDoc(body))
        val doc = RichDocCodec.decode(body)
        assertNotNull("块文档必须能解析", doc)
        assertEquals("块文档内容应与写入的标记等价", markup, LegacyBody.toText(doc!!))

        val note = repo.getNote(id)
        assertNotNull(note)
        assertEquals("读回来必须与写入的标记逐字一致", markup, note!!.body)
    }

    @Test
    fun checklistNoteIsStoredAsTodoBlocksAndItemsSurvive() = runBlocking {
        val items = listOf(ChecklistItem("甲", done = true), ChecklistItem("乙", done = false))
        val repo = NoteRepository(ctx, dbName)
        val id = repo.createNote(
            kind = NoteKind.CHECKLIST,
            title = "清单",
            body = "",
            items = items,
            images = emptyList(),
            colorIndex = 0,
            folderId = null,
        )

        val (kind, body, version) = rawRow()
        assertEquals(1, kind)
        assertEquals(3, version)
        val doc = RichDocCodec.decode(body)
        assertNotNull(doc)
        assertEquals("勾选状态必须保留", 1 to 2, doc!!.checklistProgress())
        assertTrue("清单项都应落成 TODO 块", doc.blocks.all { it.type == com.purenote.local.core.BlockType.TODO })
        assertEquals(items, repo.getNote(id)!!.items)
    }

    @Test
    fun saveExistingAlsoWritesV3() = runBlocking {
        val repo = NoteRepository(ctx, dbName)
        val id = repo.createNote(
            kind = NoteKind.TEXT, title = "t", body = "第一版",
            items = emptyList(), images = emptyList(), colorIndex = 0, folderId = null,
        )
        repo.saveExisting(
            id = id,
            kind = NoteKind.TEXT,
            title = "t",
            body = "# 改过了\n- [x] 完成",
            items = emptyList(),
            images = emptyList(),
            colorIndex = 0,
            folderId = null,
            pinned = false,
            remindAt = null,
        )

        val (_, body, version) = rawRow()
        assertEquals("修改路径也必须写 v3", 3, version)
        assertEquals("# 改过了\n- [x] 完成", LegacyBody.toText(RichDocCodec.decode(body)!!))
    }
}
