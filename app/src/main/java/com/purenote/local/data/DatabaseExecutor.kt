package com.purenote.local.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 统一数据库执行入口（规范 §6.1）。
 *
 * 为什么需要它：本仓库此前**只有一处**显式事务（BackupCodec 的导入），其余多步写操作
 * 都是裸语句序列——
 *  - `replaceSubs`：先删全部子任务，再逐条插入，再回写父项完成状态；
 *  - `deleteTodoTree`：先删子项、再删父项；
 *  - `deleteFolder`：先把笔记移出分类、再删分类。
 * 中途失败会留下半更新状态（子任务全丢、分类已删但笔记仍指向它）。
 *
 * 约定（原生 Android SQLite 的硬约束）：
 *  - [write] 的 block 是**非 suspend** 的。事务与线程绑定，事务内禁止
 *    delay / await / withContext / 启动其他协程，也不能跨线程使用同一个事务对象。
 *  - 所有 SQL 必须使用传入的这**一个** [SQLiteDatabase] 实例，不要回头取
 *    `helper.writableDatabase`/`readableDatabase`——WAL 下可能是另一条连接，
 *    看不到当前事务内的未提交数据。
 *  - 影响行数校验由调用方在 block 内完成（例如"必须恰好 1 行"）。
 *  - CancellationException 原样传播；提交结果未知时应查询操作表核实，
 *    不能当成"从未执行"（阶段 C 的 operations 表）。
 */
class DatabaseExecutor(
    private val helper: NotesDb,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val gate = Mutex()

    /** 只读访问：不需要事务，也不受写门禁影响（损坏时仍可读，便于导出抢救）。 */
    suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(ioDispatcher) {
        block(helper.readableDatabase)
    }

    /**
     * 写入统一入口：串行化 + 单事务 + 提交/回滚。
     * 数据库处于损坏保全状态时直接拒绝，避免在损坏的库上继续扩大损失。
     */
    suspend fun <T> write(block: (SQLiteDatabase) -> T): T = gate.withLock {
        withContext(ioDispatcher) {
            check(!CorruptionState.corrupted) {
                "数据库处于损坏保全状态，已拒绝写入（现场已保留）：" + (CorruptionState.detail ?: "")
            }
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val result = block(database)
                database.setTransactionSuccessful()
                result
            } finally {
                // 必须晚于实际提交；未调用 setTransactionSuccessful 时这里是回滚
                database.endTransaction()
            }
        }
    }
}
