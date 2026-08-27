package com.purenote.local.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 应用内的数据变更信号。
 *
 * 悬浮速记服务和主界面运行在同一进程，但它们各自维护界面状态。通过这个轻量
 * 事件流，悬浮层写入数据库后可以立即让已经打开的主界面重新读取数据。
 */
object DataChanges {
    private val mutableEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()

    fun notifyChanged() {
        mutableEvents.tryEmit(Unit)
    }
}
