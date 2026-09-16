package com.purenote.local.feature.notes

import com.purenote.local.data.NoteRepository
import com.purenote.local.data.SaveResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 真正执行写入的边界。默认实现直连 [NoteRepository]；测试可注入假实现，
 * 从而在不需要设备的情况下验证"同一条笔记最多一个写入在途"。
 */
fun interface SaveWriter {
    suspend fun write(command: SaveCommand): SaveResult
}

/**
 * 保存协调器（规范 §8）。
 *
 * 核心保证：**同一条笔记最多一个写入在途**。
 * 原实现的自动保存是 `snapshotFlow{revision}.debounce(500).collect{ persist() }`，
 * 快速输入、切后台、点返回都可能叠出并发写入，彼此覆盖且没有顺序保证。
 *
 * 刻意不做的事：不用 `collectLatest`/取消在途事务。一个已经进入 SQLite 的事务被取消后，
 * 调用方拿不到回执，却可能已经提交——重试就会产生重复写入（规范 §8 明令禁止）。
 * "合并"只发生在**尚未开始**的提交上，由调用方按 editGeneration 决定是否还需要发。
 */
class SaveCoordinator(private val writer: SaveWriter) {

    private val gates = ConcurrentHashMap<Long, Mutex>()

    private fun gate(noteId: Long): Mutex = gates.computeIfAbsent(noteId) { Mutex() }

    /**
     * 串行执行一次写入并等待它的明确结果。
     * 返回的 [SaveResult] 一定属于**本次**提交，不会是上一次的回执。
     */
    suspend fun save(command: SaveCommand): SaveResult =
        gate(command.noteId).withLock { writer.write(command) }

    /** 当前是否已有写入在途（供界面显示"保存中"） */
    fun isWriting(noteId: Long): Boolean = gate(noteId).isLocked

    companion object {
        /** 生产装配：把 [SaveCommand] 映射到仓库的保存入口 */
        fun forRepository(repository: NoteRepository): SaveCoordinator =
            SaveCoordinator { command ->
                repository.saveExisting(
                    id = command.noteId,
                    kind = command.kind,
                    title = command.title,
                    doc = command.doc,
                    mind = command.mind,
                    items = command.items,
                    images = command.images,
                    colorIndex = command.colorIndex,
                    folderId = command.folderId,
                    pinned = command.pinned,
                    remindAt = command.remindAt,
                    repeat = command.repeat,
                    allDay = command.allDay,
                    operationId = command.operationId,
                    expectedRevision = command.expectedRevision,
                    storeEpoch = command.storeEpoch,
                )
            }
    }
}
