package com.purenote.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 规范 §5.2：根待办的修订号是**父子整体的冲突边界**，且每次聚合变更要留完整快照。
 *
 * 这里同时覆盖一个真实缺陷：todo 的 revision 原先从来没被改过（恒为 1）。
 * 后果不只是历史缺失——reminder_jobs 记的 expected_revision 也不变，
 * 于是"旧任务不能覆盖新提醒"对**待办**完全失效。
 */
@RunWith(AndroidJUnit4::class)
class TodoAggregateVersionTest {

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
        repo.db.readableDatabase.rawQuery("SELECT revision FROM todos WHERE id = ?", arrayOf(id.toString()))
            .use { it.moveToFirst(); it.getLong(0) }

    private fun snapshotOf(rootId: Long): String =
        repo.db.readableDatabase.rawQuery(
            "SELECT snapshot_json FROM todo_versions WHERE todo_root_id = ? ORDER BY revision DESC LIMIT 1",
            arrayOf(rootId.toString()),
        ).use { it.moveToFirst(); it.getString(0) }

    @Test
    fun anAggregateChangeBumpsTheRootRevisionAndWritesASnapshot() = runBlocking {
        val parentId = repo.createTodo(parentId = null, title = "父待办", dueAt = null, allDay = false, repeat = 0)
        assertEquals("新建即 revision 1", 1L, revisionOf(parentId))
        assertEquals("新建不该产生历史", 0, TodoVersionsTable.countFor(repo.db.readableDatabase, parentId))

        repo.replaceSubs(parentId, listOf("子A" to false, "子B" to true))

        assertEquals("聚合变更必须推进根修订号", 2L, revisionOf(parentId))
        assertEquals("必须留下一条快照", 1, TodoVersionsTable.countFor(repo.db.readableDatabase, parentId))

        val snapshot = snapshotOf(parentId)
        assertTrue("快照必须含子项 A，实际=$snapshot", snapshot.contains("子A"))
        assertTrue("快照必须含子项 B", snapshot.contains("子B"))
        assertTrue("快照必须记录修订号", snapshot.contains("\"revision\":2"))
    }

    @Test
    fun everyAggregateChangeAdvancesTheRevision() = runBlocking {
        // 修订号必须单调递增，"旧任务不能覆盖新提醒"才有判据
        val parentId = repo.createTodo(parentId = null, title = "父待办", dueAt = null, allDay = false, repeat = 0)
        repo.replaceSubs(parentId, listOf("第一版" to false))
        val afterFirst = revisionOf(parentId)
        repo.replaceSubs(parentId, listOf("第二版" to false))
        val afterSecond = revisionOf(parentId)

        assertTrue("第二次聚合变更必须继续推进修订号（$afterFirst -> $afterSecond）", afterSecond > afterFirst)
        assertEquals(2, TodoVersionsTable.countFor(repo.db.readableDatabase, parentId))
    }

    private companion object {
        const val DB = "todo-aggregate-version-test.db"
    }
}
