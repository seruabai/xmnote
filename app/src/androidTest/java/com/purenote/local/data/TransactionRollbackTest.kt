package com.purenote.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 规范 §6.1 / §16：多步写操作必须整体提交或整体回滚。
 * 对应验收项「子任务丢失：删除/更新旧子任务后插入失败 → 原聚合、ID 和完成状态完全保留」。
 * 必须用真实文件库（内存库不能证明提交/回滚语义）。
 */
@RunWith(AndroidJUnit4::class)
class TransactionRollbackTest {

    private lateinit var db: NotesDb

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(DB)
        db = NotesDb(ctx, DB)
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DB)
    }

    private fun countSubs(parentId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM todos WHERE parent_id = ?",
            arrayOf(parentId.toString()),
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun subIds(parentId: Long): List<Long> =
        db.readableDatabase.rawQuery(
            "SELECT id FROM todos WHERE parent_id = ? ORDER BY id",
            arrayOf(parentId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    @Test
    fun replaceSubsRollsBackWhenInsertFails() {
        val now = 1_700_000_000_000L
        val parent = db.insertTodo(null, "父待办", null, false, 0, 0, now)
        db.insertTodo(parent, "子A", null, false, 0, 0, now)
        db.insertTodo(parent, "子B", null, false, 0, 0, now)
        val before = subIds(parent)
        assertEquals(2, before.size)

        val failed = runCatching {
            db.inTransaction { database ->
                db.deleteSubsOf(parent, database)          // 先删光
                db.insertTodo(parent, "新子", null, false, 0, 0, now, database)
                error("模拟中途失败：磁盘满 / 进程被杀")   // 再中断
            }
        }
        assertTrue("应抛出异常", failed.isFailure)

        assertEquals("原有子任务数量必须完整保留", 2, countSubs(parent))
        assertEquals("子任务 ID 必须不变", before, subIds(parent))
    }

    @Test
    fun deleteTodoTreeRollsBackAsOneUnit() {
        val now = 1_700_000_000_000L
        val parent = db.insertTodo(null, "父待办", null, false, 0, 0, now)
        db.insertTodo(parent, "子A", null, false, 0, 0, now)

        runCatching {
            db.inTransaction { database ->
                db.deleteTodoTree(parent, database)
                error("模拟中途失败")
            }
        }

        assertEquals("子项不得先被删掉", 1, countSubs(parent))
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM todos WHERE id = ?", arrayOf(parent.toString()))
            .use { c -> c.moveToFirst(); assertEquals("父项必须还在", 1, c.getInt(0)) }
    }

    @Test
    fun deleteFolderRollsBackSoNotesNeverKeepADanglingFolderId() {
        val now = 1_700_000_000_000L
        val folder = db.insertFolder("工作", now)
        val note = db.insertNote(NoteKind.TEXT, "标题", "正文", "", 0, folder, now)

        runCatching {
            db.inTransaction { database ->
                db.deleteFolder(folder, database)
                error("模拟中途失败")
            }
        }

        db.readableDatabase.rawQuery(
            "SELECT folder_id FROM notes WHERE id = ?", arrayOf(note.toString()),
        ).use { c ->
            c.moveToFirst()
            assertEquals("分类必须仍存在且笔记仍指向它", folder, c.getLong(0))
        }
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM folders WHERE id = ?", arrayOf(folder.toString()))
            .use { c -> c.moveToFirst(); assertEquals("分类不得先被删掉", 1, c.getInt(0)) }
    }

    @Test
    fun successfulTransactionCommits() {
        val now = 1_700_000_000_000L
        val parent = db.insertTodo(null, "父待办", null, false, 0, 0, now)
        db.inTransaction { database ->
            db.insertTodo(parent, "子A", null, false, 0, 0, now, database)
            db.insertTodo(parent, "子B", null, false, 0, 0, now, database)
        }
        assertEquals(2, countSubs(parent))
    }

    private companion object {
        const val DB = "tx-rollback-test.db"
    }
}
