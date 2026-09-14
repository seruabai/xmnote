package com.purenote.local.feature.notes

import com.purenote.local.data.NoteKind
import com.purenote.local.data.SaveResult
import com.purenote.local.data.StorageFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 规范 §8 / §16：回执乱序、并发写入、失败与冲突的状态迁移 */
class EditorReducerTest {

    private fun loaded() = EditorReducer.reduce(
        EditorReducer.initial("s1", "epoch-1"),
        EditorEvent.Loaded(content = "初始", revision = 7L),
    )

    @Test
    fun loadEstablishesTheBaselineRevision() {
        val s = loaded()
        assertEquals(7L, s.committedRevision)
        assertTrue(EditorReducer.canWrite(s))
        assertEquals(SaveStatus.IDLE, s.saveStatus)
    }

    @Test
    fun loadFailureMakesTheEditorReadOnlyAndBlocksWrites() {
        val s = EditorReducer.reduce(EditorReducer.initial("s", "e"), EditorEvent.LoadFailed)
        assertFalse("加载失败后不得写入", EditorReducer.canWrite(s))
        assertEquals(SaveStatus.FAILED, s.saveStatus)
    }

    @Test
    fun editingBumpsGenerationAndMarksPending() {
        val s = EditorReducer.reduce(loaded(), EditorEvent.Edited("改了一笔"))
        assertEquals(1L, s.editGeneration)
        assertEquals(SaveStatus.PENDING, s.saveStatus)
        assertTrue(s.hasUnacknowledgedEdits)
    }

    @Test
    fun lateReceiptForAnOlderGenerationMustNotClaimTheNewestIsSaved() {
        // 规范里最隐蔽的一条："看起来保存成功，实际最新内容没保存"
        var s = loaded()
        s = EditorReducer.reduce(s, EditorEvent.Edited("第 10 次输入"))
        val gen10 = s.editGeneration
        s = EditorReducer.reduce(s, EditorEvent.SaveStarted(gen10))
        s = EditorReducer.reduce(s, EditorEvent.Edited("第 11 次输入"))
        // 第 10 次的回执现在才到
        s = EditorReducer.reduce(s, EditorEvent.SaveSucceeded(gen10, revision = 8L))

        assertEquals("只标记第 10 代已确认", gen10, s.acknowledgedGeneration)
        assertEquals("当前是第 11 代，必须仍是待保存", SaveStatus.PENDING, s.saveStatus)
        assertTrue(s.hasUnacknowledgedEdits)
    }

    @Test
    fun receiptForTheLatestGenerationSettlesToIdle() {
        var s = loaded()
        s = EditorReducer.reduce(s, EditorEvent.Edited("内容"))
        val gen = s.editGeneration
        s = EditorReducer.reduce(s, EditorEvent.SaveStarted(gen))
        s = EditorReducer.reduce(s, EditorEvent.SaveSucceeded(gen, revision = 9L))
        assertEquals(SaveStatus.IDLE, s.saveStatus)
        assertFalse(s.hasUnacknowledgedEdits)
        assertEquals("已确认修订推进到 9", 9L, s.committedRevision)
    }

    @Test
    fun editingDuringSaveDoesNotHideTheInFlightState() {
        var s = loaded()
        s = EditorReducer.reduce(s, EditorEvent.Edited("内容"))
        s = EditorReducer.reduce(s, EditorEvent.SaveStarted(s.editGeneration))
        assertEquals(SaveStatus.SAVING, s.saveStatus)
        s = EditorReducer.reduce(s, EditorEvent.Edited("又改了"))
        assertEquals("写入中不应被输入打断显示", SaveStatus.SAVING, s.saveStatus)
    }

    @Test
    fun failureIsSurfacedAndEditsStayUnacknowledged() {
        var s = loaded()
        s = EditorReducer.reduce(s, EditorEvent.Edited("内容"))
        s = EditorReducer.reduce(s, EditorEvent.SaveStarted(s.editGeneration))
        s = EditorReducer.reduce(s, EditorEvent.SaveFailed("磁盘满"))
        assertEquals(SaveStatus.FAILED, s.saveStatus)
        assertTrue("失败后本地改动仍未确认", s.hasUnacknowledgedEdits)
    }

    @Test
    fun conflictKeepsLocalInputAndAdoptsTheRemoteRevision() {
        var s = loaded()
        s = EditorReducer.reduce(s, EditorEvent.Edited("本地版本"))
        s = EditorReducer.reduce(s, EditorEvent.SaveStarted(s.editGeneration))
        s = EditorReducer.reduce(s, EditorEvent.SaveConflicted(actualRevision = 12L))

        assertEquals(SaveStatus.CONFLICT, s.saveStatus)
        assertEquals("采用对方的修订号作为新基线", 12L, s.committedRevision)
        assertEquals("本地输入必须保留", "本地版本", s.content)
        assertTrue("本地改动不能被标成已保存", s.hasUnacknowledgedEdits)
    }

    @Test
    fun onlyConsecutiveConfirmedCommitsAdvanceTheBaseline() {
        var s = loaded()
        s = EditorReducer.reduce(s, EditorEvent.Edited("a"))
        s = EditorReducer.reduce(s, EditorEvent.SaveSucceeded(s.editGeneration, 8L))
        assertEquals(8L, s.committedRevision)

        s = EditorReducer.reduce(s, EditorEvent.Edited("b"))
        s = EditorReducer.reduce(s, EditorEvent.SaveSucceeded(s.editGeneration, 9L))
        assertEquals(9L, s.committedRevision)
        assertEquals(SaveStatus.IDLE, s.saveStatus)
    }
}

/** 规范 §8：同一条笔记最多一个写入在途 */
class SaveCoordinatorTest {

    private fun command(noteId: Long, gen: Long) = SaveCommand(
        noteId = noteId, operationId = "op-${noteId}-${gen}", sessionId = "s", storeEpoch = "e",
        editGeneration = gen, expectedRevision = gen,
        kind = NoteKind.TEXT, title = "t", body = "b", items = emptyList(), images = emptyList(),
        colorIndex = 0, folderId = null, pinned = false, remindAt = null,
    )

    @Test
    fun concurrentSavesForTheSameNoteNeverOverlap() = runBlocking {
        val concurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val coordinator = SaveCoordinator { cmd ->
            val now = concurrent.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, now) }
            delay(30)
            concurrent.decrementAndGet()
            SaveResult.Saved(cmd.noteId, 1L)
        }

        (1..8).map { gen -> async { coordinator.save(command(1L, gen.toLong())) } }.awaitAll()

        assertEquals("同一条笔记的写入必须串行，实测最大并发 " + maxObserved.get(), 1, maxObserved.get())
    }

    @Test
    fun differentNotesCanWriteInParallel() = runBlocking {
        val concurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val coordinator = SaveCoordinator { cmd ->
            val now = concurrent.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, now) }
            delay(40)
            concurrent.decrementAndGet()
            SaveResult.Saved(cmd.noteId, 1L)
        }

        listOf(1L, 2L, 3L).map { id -> async { coordinator.save(command(id, 1L)) } }.awaitAll()

        assertTrue("不同笔记不应互相阻塞，实测最大并发 " + maxObserved.get(), maxObserved.get() > 1)
    }

    @Test
    fun failureOfOneSaveIsReturnedToItsOwnCaller() = runBlocking {
        val coordinator = SaveCoordinator { cmd ->
            if (cmd.editGeneration == 2L) SaveResult.Failed(StorageFailure.NO_SPACE)
            else SaveResult.Saved(cmd.noteId, cmd.editGeneration)
        }
        val first = coordinator.save(command(1L, 1L))
        val second = coordinator.save(command(1L, 2L))
        val third = coordinator.save(command(1L, 3L))

        assertTrue(first is SaveResult.Saved)
        assertEquals(SaveResult.Failed(StorageFailure.NO_SPACE), second)
        assertTrue("前一次失败不得污染后续提交", third is SaveResult.Saved)
        assertEquals(3L, (third as SaveResult.Saved).revision)
    }
}
