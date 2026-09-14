package com.purenote.local.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase

/**
 * 数据库损坏状态（全局，进程内）。
 * 一旦置位，写入路径必须拒绝继续写入，避免在损坏的库上扩大损失。
 */
object CorruptionState {
    @Volatile
    var corrupted: Boolean = false
        private set

    @Volatile
    var detail: String? = null
        private set

    fun mark(detail: String) {
        corrupted = true
        this.detail = detail
    }
}

/**
 * 保全型数据库损坏处理器（规范 §6.3）。
 *
 * Android 默认的 [android.database.DefaultDatabaseErrorHandler] 在 onCorruption 中
 * **会删除数据库文件**（连同 journal/WAL）。对"数据只存在本机、没有云端副本"的笔记应用，
 * 这等于把用户全部笔记换成一个空库。
 *
 * 本处理器只做三件事：置故障状态、留下不含正文的诊断、让上层决定如何保全现场。
 * 刻意不做：deleteDatabase、删表重建、onCreate、递归获取 writableDatabase。
 *
 * @param onCorrupted 供测试注入；生产默认写入 [CorruptionState]。
 */
class PreserveDatabaseErrorHandler(
    private val onCorrupted: (String) -> Unit = { CorruptionState.mark(it) },
) : DatabaseErrorHandler {

    override fun onCorruption(dbObj: SQLiteDatabase) {
        // 只记录路径，不记录任何笔记内容
        val path = runCatching { dbObj.path }.getOrNull() ?: "<unknown>"
        onCorrupted("database corruption: " + path)
    }
}
