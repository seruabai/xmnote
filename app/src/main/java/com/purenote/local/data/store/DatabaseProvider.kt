package com.purenote.local.data.store

import android.content.Context
import com.purenote.local.data.NotesDb

/** 指针指向的库文件不存在，但应用并不处于首次运行：必须走恢复，不能静默建空库。 */
class StoreMissingException(val pointer: StorePointer) :
    IllegalStateException(
        "活动存储指针指向的数据库不存在：${pointer.dbName}（epoch=${pointer.epoch}）。" +
            "已进入恢复状态，未创建空库。",
    )

sealed interface StoreStatus {
    data class Ready(val epoch: String, val dbName: String, val justInitialized: Boolean) : StoreStatus
    data class Missing(val pointer: StorePointer, val recovery: RecoveryRecord?) : StoreStatus
}

/**
 * 活动存储的唯一提供者（规范 §6.1 / §12）。
 *
 * 全应用只有这一处打开数据库：Activity、ViewModel、悬浮速递服务、提醒 Receiver
 * 都不自行构造 NotesDb。这样"换一代存储"才可能是原子的——只有这里知道指针。
 *
 * [require] 必须在 IO 线程调用（首次会读 active.json，可能创建库文件）。
 */
class DatabaseProvider(
    private val context: Context,
    private val control: StoreControl,
) {

    private val lock = Any()

    @Volatile
    private var current: NotesDb? = null

    @Volatile
    private var currentPointer: StorePointer? = null

    /** 指针是否由本进程首次生成（首次运行才允许创建库文件）。 */
    @Volatile
    private var pointerJustInitialized: Boolean = false

    /**
     * 解析活动指针（小文件 IO，调用点都在 IO 或初始化路径上）。
     *
     * 必须能独立于"打开数据库"发生：否则 [storeEpoch] 在首次访问库之前返回空串，
     * 写入命令携带的就是空代次，§12 第 9 条的旧写入隔离会被静默跳过。
     */
    private fun ensurePointer(): StorePointer = synchronized(lock) {
        currentPointer ?: control.active().let {
            pointerJustInitialized = it.justInitialized
            currentPointer = it.pointer
            it.pointer
        }
    }

    /** 当前存储代次；写入命令携带它，用于拒绝"切换之后才到达的旧写入"（规范 §12 第 9 条）。 */
    val storeEpoch: String get() = ensurePointer().epoch

    fun status(): StoreStatus {
        val pointer = ensurePointer()
        return if (context.getDatabasePath(pointer.dbName).let { it.exists() && it.length() > 0 }) {
            StoreStatus.Ready(pointer.epoch, pointer.dbName, pointerJustInitialized)
        } else if (pointerJustInitialized) {
            // 首次运行：文件确实还不存在，允许创建
            StoreStatus.Ready(pointer.epoch, pointer.dbName, true)
        } else {
            StoreStatus.Missing(pointer, control.readRecovery())
        }
    }

    /**
     * 打开（必要时创建）活动库。
     * 指针存在而库文件消失时抛 [StoreMissingException]：
     * 宁可进入恢复选择，也不要用一个空库覆盖用户"以为还在"的笔记。
     */
    fun require(): NotesDb {
        current?.let { return it }
        return synchronized(lock) {
            current?.let { return it }
            val resolved = ensurePointer()
            val file = context.getDatabasePath(resolved.dbName)
            val exists = file.exists() && file.length() > 0
            if (!exists && !pointerJustInitialized) {
                throw StoreMissingException(resolved)
            }
            open(resolved).also {
                current = it
                currentPointer = resolved
            }
        }
    }

    /**
     * 切到新一代存储（规范 §12 第 8 步）：
     * 先关闭旧实例，再发布指针，最后打开新库并核对。
     * 发布指针之前新库必须已经建好并验证过（由调用方保证）。
     */
    fun switchTo(pointer: StorePointer) {
        synchronized(lock) {
            current?.close()
            current = null
            currentPointer = null
            control.publish(pointer)
            val opened = open(pointer)
            current = opened
            currentPointer = pointer
            // 已经是"运行中"的存储，后续若文件消失必须进入恢复状态而不是重建空库
            pointerJustInitialized = false
        }
    }

    fun close() {
        synchronized(lock) {
            current?.close()
            current = null
            currentPointer = null
        }
    }

    /** 只打开指定指针的库（恢复流程用来验证新库，不影响当前活动库）。 */
    fun openForVerification(pointer: StorePointer): NotesDb = open(pointer)

    private fun open(pointer: StorePointer): NotesDb = NotesDb(context, pointer.dbName)
}
