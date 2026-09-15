package com.purenote.local.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.core.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 规范 §10：文件发布成功后必须把元数据（大小 + SHA-256）写入 attachments；
 * 正文引用的提交必须在文件完整发布之后。
 */
@RunWith(AndroidJUnit4::class)
class AttachmentMetadataTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        ctx.deleteDatabase(DB)
        repo = NoteRepository(ctx, DB)
    }

    @After
    fun tearDown() {
        ImageStore.onPublished = null
        ctx.deleteDatabase(DB)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    @Test
    fun publishingAnImageRecordsSizeAndHash() {
        var recorded: com.purenote.local.data.AttachmentStore.Published? = null
        ImageStore.onPublished = { recorded = it }
        try {
            val name = ImageStore.saveBitmap(ctx, bitmap())
            assertNotNull("发布应成功", name)
            assertNotNull("发布成功后必须回调登记元数据", recorded)
            assertEquals("登记的文件名必须与实际发布的一致", name, recorded!!.fileName)
            assertTrue("必须记录大小", recorded!!.sizeBytes > 0)
            assertEquals("必须记录 64 位十六进制 SHA-256", 64, recorded!!.sha256.length)
        } finally {
            ImageStore.onPublished = null
        }
    }

    @Test
    fun theRecordedMetadataLandsInTheAttachmentsTable() = runBlocking {
        ImageStore.onPublished = { published ->
            runBlocking {
                repo.recordAttachment(
                    published.attachmentId, published.fileName, published.sizeBytes, published.sha256,
                )
            }
        }
        val name = ImageStore.saveBitmap(ctx, bitmap())!!
        ImageStore.onPublished = null

        assertEquals("attachments 表里必须有这一条", 1, repo.debugAttachmentCount(name))
        ImageStore.deleteFile(ctx, name)
    }

    @Test
    fun savingANoteRecordsItsAttachmentReferences() = runBlocking {
        // 正文里引用两个附件；其中一个是录音
        val body = "记录\n![](img_a.jpg)\n![](aud_b.m4a)"
        val id = repo.createNote(NoteKind.TEXT, "带附件", body, emptyList(), emptyList(), 0, null)
        repo.saveExisting(
            id, NoteKind.TEXT, "带附件", body, emptyList(),
            listOf("img_a.jpg", "aud_b.m4a"), 0, null, false, null,
        )

        val refs = repo.db.readableDatabase.rawQuery(
            "SELECT attachment_id FROM note_attachment_refs WHERE note_id = ? ORDER BY attachment_id",
            arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("aud_b.m4a", "img_a.jpg"), refs)
    }

    @Test
    fun refsArePrunedToWhatTheCurrentBodyReferences() = runBlocking {
        val id = repo.createNote(NoteKind.TEXT, "t", "![](a.jpg)", emptyList(), listOf("a.jpg"), 0, null)
        repo.saveExisting(id, NoteKind.TEXT, "t", "![](a.jpg)", emptyList(), listOf("a.jpg"), 0, null, false, null)
        // 把图片从正文里去掉
        repo.saveExisting(id, NoteKind.TEXT, "t", "没有图片了", emptyList(), emptyList(), 0, null, false, null)

        val count = repo.db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM note_attachment_refs WHERE note_id = ?", arrayOf(id.toString()),
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals("不再被正文引用的当前版本引用应被清理", 0, count)
    }

    @Test
    fun eachRevisionRecordsItsOwnAttachmentReferences() = runBlocking {
        // 规范 §5.2/§10：历史版本的引用必须单独留档。
        // 只记当前版本的话，一旦用户回滚到旧版本，那些附件会被当成垃圾清掉，
        // 用户看到的就是一堆破图。
        val id = repo.createNote(NoteKind.TEXT, "t", "![](v1.jpg)", emptyList(), listOf("v1.jpg"), 0, null)

        repo.saveExisting(id, NoteKind.TEXT, "t", "![](v1.jpg)", emptyList(), listOf("v1.jpg"), 0, null, false, null)
        repo.saveExisting(id, NoteKind.TEXT, "t", "![](v2.jpg)", emptyList(), listOf("v2.jpg"), 0, null, false, null)

        val byRevision = repo.db.readableDatabase.rawQuery(
            "SELECT revision, attachment_id FROM version_attachment_refs WHERE note_id = ? ORDER BY revision, attachment_id",
            arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }

        assertEquals(2, byRevision.size)
        assertEquals("第 2 版引用 v1", 2L to "v1.jpg", byRevision[0])
        assertEquals("第 3 版引用 v2", 3L to "v2.jpg", byRevision[1])

        // 当前版本的引用只剩 v2；v1 的历史引用必须还在
        val current = repo.db.readableDatabase.rawQuery(
            "SELECT attachment_id FROM note_attachment_refs WHERE note_id = ?", arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("v2.jpg"), current)
    }

    private companion object {
        const val DB = "attachment-metadata-test.db"
    }
}
