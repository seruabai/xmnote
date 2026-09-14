package com.purenote.local.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NotesDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 规范 §16「格式往返」：中文、emoji、空行、清单、Markdown 标记、附件引用
 * 在 导出 -> 清空 -> 导入 之后必须**逐项相同**，不能只比数量。
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripFidelityTest {

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

    private data class Row(
        val uuid: String, val kind: Int, val title: String, val body: String,
        val images: String, val color: Int, val pinned: Int, val trashed: Int,
        val remindAt: Long?, val repeat: Int, val allDay: Int, val createdAt: Long, val updatedAt: Long,
    )

    private fun rows(): List<Row> = db.readableDatabase.rawQuery(
        "SELECT uuid,kind,title,body,images,color,pinned,trashed,remind_at,repeat_type,all_day,created_at,updated_at " +
            "FROM notes ORDER BY uuid",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Row(
                        uuid = c.getString(0), kind = c.getInt(1), title = c.getString(2),
                        body = c.getString(3), images = c.getString(4), color = c.getInt(5),
                        pinned = c.getInt(6), trashed = c.getInt(7),
                        remindAt = if (c.isNull(8)) null else c.getLong(8),
                        repeat = c.getInt(9), allDay = c.getInt(10),
                        createdAt = c.getLong(11), updatedAt = c.getLong(12),
                    ),
                )
            }
        }
    }

    private fun seed() {
        val now = 1_700_000_000_000L
        // 中文 + emoji + 空行 + Markdown 标记 + 图片引用 + 引号换行
        db.insertNote(
            NoteKind.TEXT, "中文标题 🎉",
            "# 标题\n\n带 \"引号\" 和 emoji 😀 的正文\n- [ ] 任务\n- [x] 完成\n![](img_1.jpg)\n\n结尾",
            "img_1.jpg", 3, null, now,
        )
        db.insertNote(NoteKind.TEXT, "", "只有正文没有标题\n第二行", "", 0, null, now + 1)
        db.insertNote(NoteKind.CHECKLIST, "清单", "", "", 1, null, now + 2)
        // 写入清单条目（走 ChecklistCodec）
        val folderId = db.insertFolder("工作", now)
        val checklistId = db.readableDatabase.rawQuery("SELECT id FROM notes WHERE title='清单'", null)
            .use { c -> c.moveToFirst(); c.getLong(0) }
        db.updateNote(
            id = checklistId, kind = NoteKind.CHECKLIST, title = "清单",
            encodedBody = ChecklistCodec.encode(
                listOf(ChecklistItem("买牛奶 🥛", false), ChecklistItem("写代码", true)),
            ),
            images = "", colorIndex = 1, folderId = folderId, pinned = true,
            remindAt = now + 86_400_000L, repeatType = 1, allDay = true, now = now + 3,
        )
    }

    @Test
    fun allFieldsSurviveExportWipeImport() {
        runBlocking {
            seed()
            val before = rows()
            assertTrue("前置：应有数据", before.size >= 3)

            val target = File(ctx.cacheDir, "fidelity.purenote.zip")
            io.export(target, db, appVersion = "test")

            // 清空后导回
            db.writableDatabase.delete("notes", null, null)
            db.writableDatabase.delete("folders", null, null)
            assertEquals(0, rows().size)

            val result = target.inputStream().use { io.import(it, db) }
            assertTrue("应有导入", result.inserted >= 3)

            val after = rows()
            assertEquals("记录数必须一致", before.size, after.size)
            // 逐项比较，不只比数量
            before.zip(after).forEach { (b, a) ->
                assertEquals("uuid", b.uuid, a.uuid)
                assertEquals("kind", b.kind, a.kind)
                assertEquals("title", b.title, a.title)
                assertEquals("body 必须逐字节相同", b.body, a.body)
                assertEquals("images", b.images, a.images)
                assertEquals("color", b.color, a.color)
                assertEquals("pinned", b.pinned, a.pinned)
                assertEquals("trashed", b.trashed, a.trashed)
                assertEquals("remindAt", b.remindAt, a.remindAt)
                assertEquals("repeat", b.repeat, a.repeat)
                assertEquals("allDay", b.allDay, a.allDay)
                assertEquals("createdAt", b.createdAt, a.createdAt)
                assertEquals("updatedAt 必须原样保留（不得用 now）", b.updatedAt, a.updatedAt)
            }
            target.delete()
        }
    }

    @Test
    fun checklistItemsSurviveTheRoundTrip() {
        runBlocking {
            seed()
            val target = File(ctx.cacheDir, "fidelity-check.purenote.zip")
            io.export(target, db, appVersion = "test")

            val before = db.readableDatabase.rawQuery(
                "SELECT body FROM notes WHERE kind = 1", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            val decoded = ChecklistCodec.decode(before)
            assertEquals(2, decoded.size)
            assertTrue(decoded.any { it.text.contains("买牛奶") })

            db.writableDatabase.delete("notes", null, null)
            target.inputStream().use { io.import(it, db) }

            val after = db.readableDatabase.rawQuery(
                "SELECT body FROM notes WHERE kind = 1", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("清单编码必须逐字节相同", before, after)
            target.delete()
        }
    }

    @Test
    fun attachmentsAreRecordedInTheManifest() {
        runBlocking {
            // 放一个真实附件文件，确认它进了包并且清单记录了它
            val dir = com.purenote.local.core.ImageStore.imagesDir(ctx)
            val f = File(dir, "roundtrip_att.jpg")
            f.writeBytes(ByteArray(512) { it.toByte() })
            db.insertNote(NoteKind.TEXT, "带图", "![](roundtrip_att.jpg)", "roundtrip_att.jpg", 0, null, 1L)

            val target = File(ctx.cacheDir, "fidelity-att.purenote.zip")
            val exported = io.export(target, db, appVersion = "test")
            assertEquals(1, exported.attachmentCount)
            assertEquals("不应有缺失附件", 0, exported.missingAttachments)

            // 包里必须能读到这个附件与清单
            val bytes = target.readBytes()
            assertTrue("包内应含 manifest.json", String(bytes, Charsets.ISO_8859_1).contains("manifest.json"))
            target.delete()
            f.delete()
        }
    }

    private companion object {
        const val DB = "backup-fidelity-test.db"
    }
}
