package com.purenote.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 规范 §7 / §5.3 / §16：
 * 并发覆盖只能成功一个、同 operationId 重放不产生第二次修改、每次提交留下历史快照。
 */
@RunWith(AndroidJUnit4::class)
class RevisionAndIdempotencyTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        ctx.deleteDatabase(DB)
        repo = NoteRepository(ctx, DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(DB)
    }

    private fun revisionOf(id: Long): Long =
        repo.debugRevision(id)

    private fun versionCount(id: Long): Int = repo.debugVersionCount(id)

    @Test
    fun eachSaveAdvancesRevisionAndWritesHistory() = runBlocking {
        val id = repo.createNote(NoteKind.TEXT, "标题", "正文 v1", emptyList(), emptyList(), 0, null)
        assertEquals("新建即 revision 1", 1L, revisionOf(id))

        val first = repo.saveExisting(id, NoteKind.TEXT, "标题", "正文 v2", emptyList(), emptyList(), 0, null, false, null)
        assertTrue("首次保存应成功", first is SaveResult.Saved)
        assertEquals(2L, (first as SaveResult.Saved).revision)
        assertEquals(2L, revisionOf(id))

        val second = repo.saveExisting(id, NoteKind.TEXT, "标题", "正文 v3", emptyList(), emptyList(), 0, null, false, null)
        assertEquals(3L, (second as SaveResult.Saved).revision)

        assertEquals("每次提交各留一份快照", 2, versionCount(id))
    }

    @Test
    fun staleExpectedRevisionIsReportedAsConflictAndDoesNotOverwrite() = runBlocking {
        val id = repo.createNote(NoteKind.TEXT, "标题", "原始", emptyList(), emptyList(), 0, null)

        // 会话 A 读到 revision=1，成功写到 2
        val a = repo.saveExisting(
            id, NoteKind.TEXT, "标题", "A 的内容", emptyList(), emptyList(), 0, null, false, null,
            expectedRevision = 1L,
        )
        assertTrue(a is SaveResult.Saved)

        // 会话 B 也拿着 revision=1 —— 必须冲突，且**不得**覆盖 A 的内容
        val b = repo.saveExisting(
            id, NoteKind.TEXT, "标题", "B 的内容", emptyList(), emptyList(), 0, null, false, null,
            expectedRevision = 1L,
        )
        assertTrue("过期修订号必须报冲突，实际=$b", b is SaveResult.Conflict)
        assertEquals(2L, (b as SaveResult.Conflict).actualRevision)

        assertEquals("A 的内容必须保留", 2L, revisionOf(id))
        assertEquals("冲突不得写入新版本", 1, versionCount(id))
    }

    @Test
    fun replayingTheSameOperationIdDoesNotWriteTwice() = runBlocking {
        val id = repo.createNote(NoteKind.TEXT, "标题", "初始", emptyList(), emptyList(), 0, null)
        val opId = "fixed-operation-id"

        val first = repo.saveExisting(
            id, NoteKind.TEXT, "标题", "内容", emptyList(), emptyList(), 0, null, false, null,
            operationId = opId,
        )
        val replay = repo.saveExisting(
            id, NoteKind.TEXT, "标题", "内容", emptyList(), emptyList(), 0, null, false, null,
            operationId = opId,
        )

        assertEquals("重放应返回同一个修订号", (first as SaveResult.Saved).revision, (replay as SaveResult.Saved).revision)
        assertEquals("重放不得再推进修订号", 2L, revisionOf(id))
        assertEquals("重放不得多写历史", 1, versionCount(id))
    }

    @Test
    fun reusedOperationIdWithDifferentContentIsRejected() = runBlocking {
        val id = repo.createNote(NoteKind.TEXT, "标题", "初始", emptyList(), emptyList(), 0, null)
        repo.saveExisting(id, NoteKind.TEXT, "标题", "内容甲", emptyList(), emptyList(), 0, null, false, null, operationId = "op-x")

        val bad = repo.saveExisting(
            id, NoteKind.TEXT, "标题", "内容乙", emptyList(), emptyList(), 0, null, false, null,
            operationId = "op-x",
        )
        assertTrue("同 ID 不同请求必须被拒绝，实际=$bad", bad is SaveResult.Failed)
        assertEquals("被拒绝的请求不得改动数据", 2L, revisionOf(id))
    }

    @Test
    fun missingNoteReportsNotFoundInsteadOfSilentlySucceeding() = runBlocking {
        val result = repo.saveExisting(
            999_999L, NoteKind.TEXT, "t", "b", emptyList(), emptyList(), 0, null, false, null,
        )
        assertEquals(SaveResult.NotFound, result)
    }

    private companion object {
        const val DB = "revision-idempotency-test.db"
    }
}
