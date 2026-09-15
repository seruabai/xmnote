package com.purenote.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.notify.ReminderJob
import com.purenote.local.notify.Reminders
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 规范 §9：待办的提醒期望必须与数据在**同一个事务**里落库，
 * 平台侧再由协调器按期望对齐。这是"数据提交与系统提醒分离"的落地。
 */
@RunWith(AndroidJUnit4::class)
class TodoReminderJobTest {

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

    private fun jobFor(targetId: Long): ReminderJob? =
        repo.db.readableDatabase.rawQuery(
            "SELECT expected_revision, desired_at, state FROM reminder_jobs " +
                "WHERE target_kind = ? AND target_id = ?",
            arrayOf(Reminders.KIND_TODO, targetId.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                ReminderJob(
                    Reminders.KIND_TODO, targetId, c.getLong(0),
                    if (c.isNull(1)) null else c.getLong(1), c.getString(2),
                )
            } else {
                null
            }
        }

    private fun revisionOf(id: Long): Long =
        repo.db.readableDatabase.rawQuery("SELECT revision FROM todos WHERE id = ?", arrayOf(id.toString()))
            .use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun creatingATodoWithADueDateRegistersTheExpectation() = runBlocking {
        val due = System.currentTimeMillis() + 3_600_000L
        val id = repo.createTodo(parentId = null, title = "交房租", dueAt = due, allDay = false, repeat = 0)

        val job = jobFor(id)
        assertNotNull("新建带到期时间的待办必须登记提醒期望", job)
        assertEquals("期望的提醒时间应为到期时间", due, job!!.desiredAt)
        assertEquals("期望携带的修订号必须与库中一致", revisionOf(id), job.expectedRevision)
        assertEquals(ReminderJob.STATE_PENDING, job.state)
    }

    @Test
    fun editingTheDueDateUpdatesTheExpectation() = runBlocking {
        val first = System.currentTimeMillis() + 3_600_000L
        val id = repo.createTodo(parentId = null, title = "交房租", dueAt = first, allDay = false, repeat = 0)

        val second = first + 86_400_000L
        repo.updateTodo(id, "交房租", second, allDay = false, repeat = 0)

        val job = jobFor(id)
        assertNotNull(job)
        assertEquals("改到期时间必须反映到期望里", second, job!!.desiredAt)
        assertEquals("必须回到 pending 等待推送", ReminderJob.STATE_PENDING, job.state)
    }

    @Test
    fun aSubtaskDoesNotGetItsOwnExpectationButFollowsTheRoot() = runBlocking {
        val due = System.currentTimeMillis() + 3_600_000L
        val parent = repo.createTodo(parentId = null, title = "父待办", dueAt = due, allDay = false, repeat = 0)
        val child = repo.createTodo(parentId = parent, title = "子项", dueAt = null, allDay = false, repeat = 0)

        // 期望写在根上；子项不单独排提醒（否则一个清单会弹一堆通知）
        assertNotNull("根必须登记期望", jobFor(parent))
        val childJob = jobFor(child)
        assertTrue(
            "子项不得单独登记期望，实际=$childJob",
            childJob == null || childJob.expectedRevision == jobFor(parent)!!.expectedRevision,
        )
    }

    @Test
    fun completingTheRootClearsTheExpectation() = runBlocking {
        val due = System.currentTimeMillis() + 3_600_000L
        val id = repo.createTodo(parentId = null, title = "交房租", dueAt = due, allDay = false, repeat = 0)
        val todo = repo.getTodo(id)!!
        assertEquals(due, jobFor(id)!!.desiredAt)

        repo.setTodoDone(todo.copy(done = false), true)

        assertEquals("完成的待办不应再提醒", null, jobFor(id)!!.desiredAt)
    }

    private companion object {
        const val DB = "todo-reminder-job-test.db"
    }
}
