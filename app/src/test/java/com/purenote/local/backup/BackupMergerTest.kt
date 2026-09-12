package com.purenote.local.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并规则是备份功能里最容易出错的部分（丢数据/覆盖新内容），
 * 这里把每条规则都钉死。applier 只负责执行计划，不承担这些判断。
 */
class BackupMergerTest {

    private fun note(
        uuid: String,
        updatedAt: Long,
        title: String = "笔记",
        folderUuid: String? = null,
    ) = NoteDto(
        uuid = uuid, kind = 0, title = title, body = "正文",
        folderUuid = folderUuid, createdAt = 1L, updatedAt = updatedAt,
    )

    private fun todo(
        uuid: String,
        updatedAt: Long,
        title: String = "待办",
        parentUuid: String? = null,
    ) = TodoDto(
        uuid = uuid, parentUuid = parentUuid, title = title,
        createdAt = 1L, updatedAt = updatedAt,
    )

    private val emptyLocal = BackupMerger.LocalSnapshot(
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    )

    // ---- 笔记：LWW ----

    @Test
    fun newNote_isInserted() {
        val plan = BackupMerger.plan(BackupFile(notes = listOf(note("n1", 100))), emptyLocal)
        assertEquals(1, plan.notes.size)
        assertTrue(plan.notes[0] is BackupMerger.NoteAction.Insert)
    }

    @Test
    fun localMissingNote_isNotSkipped() {
        val local = emptyLocal.copy(noteIdsByUuid = mapOf("other" to 5L))
        val plan = BackupMerger.plan(BackupFile(notes = listOf(note("n1", 100))), local)
        assertTrue(plan.notes[0] is BackupMerger.NoteAction.Insert)
    }

    @Test
    fun newerIncomingNote_overwritesLocal() {
        val local = emptyLocal.copy(
            noteIdsByUuid = mapOf("n1" to 7L),
            noteUpdatedAtByUuid = mapOf("n1" to 100L),
        )
        val plan = BackupMerger.plan(BackupFile(notes = listOf(note("n1", 200))), local)
        val action = plan.notes[0] as BackupMerger.NoteAction.Update
        assertEquals(7L, action.localId)
    }

    @Test
    fun olderIncomingNote_isSkipped() {
        val local = emptyLocal.copy(
            noteIdsByUuid = mapOf("n1" to 7L),
            noteUpdatedAtByUuid = mapOf("n1" to 300L),
        )
        val plan = BackupMerger.plan(BackupFile(notes = listOf(note("n1", 200))), local)
        assertTrue(plan.notes[0] is BackupMerger.NoteAction.Skip)
    }

    /** 相同时间戳跳过：保证重复导入同一份备份是幂等的（不会反复重写）。 */
    @Test
    fun sameTimestamp_isSkipped_soReimportIsIdempotent() {
        val local = emptyLocal.copy(
            noteIdsByUuid = mapOf("n1" to 7L),
            noteUpdatedAtByUuid = mapOf("n1" to 200L),
        )
        val plan = BackupMerger.plan(BackupFile(notes = listOf(note("n1", 200))), local)
        assertTrue(plan.notes[0] is BackupMerger.NoteAction.Skip)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun blankNoteUuid_treatedAsNewInsert() {
        val local = emptyLocal.copy(noteIdsByUuid = mapOf("" to 7L))
        val plan = BackupMerger.plan(BackupFile(notes = listOf(note("", 100))), local)
        assertTrue(plan.notes[0] is BackupMerger.NoteAction.Insert)
        assertTrue(plan.warnings.any { "uuid" in it })
    }

    // ---- 分类 ----

    @Test
    fun folderMatchingByName_isReused() {
        val backup = BackupFile(
            folders = listOf(FolderDto("f1", "工作", 1L)),
            notes = listOf(note("n1", 100, folderUuid = "f1")),
        )
        val local = emptyLocal.copy(folderIdByName = mapOf("工作" to 42L))
        val plan = BackupMerger.plan(backup, local)

        val folderAction = plan.folders[0] as BackupMerger.FolderAction.Reuse
        assertEquals(42L, folderAction.localId)
        assertEquals("工作", (plan.notes[0] as BackupMerger.NoteAction.Insert).folderName)
    }

    @Test
    fun newFolder_isCreated_withTrimmedName() {
        val backup = BackupFile(folders = listOf(FolderDto("f1", "  生活  ", 5L)))
        val plan = BackupMerger.plan(backup, emptyLocal)
        val action = plan.folders[0] as BackupMerger.FolderAction.Create
        assertEquals("生活", action.name)
        assertEquals(5L, action.createdAt)
    }

    @Test
    fun blankFolderName_isSkippedWithWarning() {
        val backup = BackupFile(folders = listOf(FolderDto("f1", "   ", 5L)))
        val plan = BackupMerger.plan(backup, emptyLocal)
        assertTrue(plan.folders.isEmpty())
        assertTrue(plan.warnings.any { "分类" in it })
    }

    @Test
    fun noteReferencingUnknownFolder_losesFolderWithWarning() {
        val plan = BackupMerger.plan(
            BackupFile(notes = listOf(note("n1", 100, folderUuid = "missing"))),
            emptyLocal,
        )
        assertEquals(null, (plan.notes[0] as BackupMerger.NoteAction.Insert).folderName)
        assertTrue(plan.warnings.any { "分类" in it })
    }

    // ---- 待办与父子 ----

    @Test
    fun childTodo_keepsParentReference() {
        val backup = BackupFile(
            todos = listOf(todo("parent", 100), todo("child", 100, parentUuid = "parent")),
        )
        val plan = BackupMerger.plan(backup, emptyLocal)
        val child = plan.todos.first { it.let { a ->
            (a as? BackupMerger.TodoAction.Insert)?.dto?.uuid == "child"
        } } as BackupMerger.TodoAction.Insert
        assertEquals("parent", child.parentUuid)
    }

    /** 父项缺失时不能丢内容：提升为顶层并记警告。 */
    @Test
    fun orphanChild_isPromotedToTopLevel_withWarning() {
        val backup = BackupFile(todos = listOf(todo("child", 100, parentUuid = "ghost")))
        val plan = BackupMerger.plan(backup, emptyLocal)
        val action = plan.todos[0] as BackupMerger.TodoAction.Insert
        assertEquals(null, action.parentUuid)
        assertTrue(plan.warnings.any { "顶层待办" in it })
    }

    @Test
    fun childWhoseParentIsLocalOnly_isResolved() {
        val local = emptyLocal.copy(todoIdsByUuid = mapOf("parent" to 9L))
        val backup = BackupFile(todos = listOf(todo("child", 100, parentUuid = "parent")))
        val plan = BackupMerger.plan(backup, local)
        val action = plan.todos[0] as BackupMerger.TodoAction.Insert
        assertEquals("parent", action.parentUuid)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun newerTodo_updatesOlderSkips() {
        val local = emptyLocal.copy(
            todoIdsByUuid = mapOf("t1" to 1L, "t2" to 2L),
            todoUpdatedAtByUuid = mapOf("t1" to 100L, "t2" to 500L),
        )
        val backup = BackupFile(todos = listOf(todo("t1", 200), todo("t2", 200)))
        val plan = BackupMerger.plan(backup, local)
        assertTrue(plan.todos[0] is BackupMerger.TodoAction.Update)
        assertTrue(plan.todos[1] is BackupMerger.TodoAction.Skip)
    }

    // ---- 空备份 ----

    @Test
    fun emptyBackup_producesEmptyPlan() {
        val plan = BackupMerger.plan(BackupFile(), emptyLocal)
        assertTrue(plan.isEmpty)
        assertTrue(plan.warnings.isEmpty())
    }
}
