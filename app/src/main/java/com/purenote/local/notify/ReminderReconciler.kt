package com.purenote.local.notify

/**
 * 提醒的期望状态（规范 §9）。
 *
 * 关键点：提醒**不是**"保存成功后顺手调一下 AlarmManager"，
 * 而是"数据库里记下期望状态，再由协调器按期望状态去对齐系统闹钟"。
 * 前者在保存失败时照样会重排提醒，语义是错的——数据没落库，提醒却变了。
 */
data class ReminderJob(
    val targetKind: String,
    val targetId: Long,
    /** 登记这条期望时目标记录的修订号；不一致说明目标已经又被改过 */
    val expectedRevision: Long,
    /** null = 期望"没有提醒" */
    val desiredAt: Long?,
    val state: String = STATE_PENDING,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_APPLIED = "applied"
    }
}

/** 目标记录当前的真实状态；[revision] 用于判定期望是否过期 */
data class ReminderTarget(
    val kind: String,
    val id: Long,
    val revision: Long,
    val remindAt: Long?,
)

/** 协调器需要的数据访问（由仓库实现，接口化是为了能在 JVM 上测协调逻辑本身） */
interface ReminderStore {
    /** 尚未推送到平台的期望 */
    suspend fun pendingJobs(): List<ReminderJob>
    /** 历史上登记过的全部目标（用于全量重算时把失效闹钟也取消掉） */
    suspend fun knownTargets(): List<Pair<String, Long>>
    /** 数据库现状：当前所有可能需要提醒的记录 */
    suspend fun desiredTargets(): List<ReminderTarget>
    /** 单个目标的现状；不存在返回 null */
    suspend fun findTarget(kind: String, targetId: Long): ReminderTarget?
    /** 登记期望状态（与数据变更同事务时由调用方保证） */
    suspend fun upsertJob(kind: String, targetId: Long, revision: Long, remindAt: Long?)
    suspend fun markApplied(kind: String, targetId: Long)
}

/** 平台闹钟抽象，便于在没有设备时测试协调逻辑 */
interface AlarmSink {
    fun schedule(kind: String, targetId: Long, at: Long)
    fun cancel(kind: String, targetId: Long)
}

/**
 * 提醒协调器（规范 §9）。
 *
 * - [applyPending]：数据提交之后把待应用的期望推给平台。
 *   **先核对目标的最新修订号**：过期期望直接丢弃、绝不碰平台闹钟——
 *   "旧任务不能覆盖新提醒"就是这条。
 * - [reconcileAll]：应用启动与恢复之后，按数据库现状全量对齐：
 *   先把登记过的目标全部取消，再按现状重建，最后登记新的期望。
 *   不做这一步，被误删或换机恢复后残留的闹钟会在错误的时间弹出来。
 */
class ReminderReconciler(
    private val store: ReminderStore,
    private val alarms: AlarmSink,
) {

    data class Summary(val applied: Int, val canceled: Int, val staleSkipped: Int)

    suspend fun applyPending(): Summary {
        var applied = 0
        var canceled = 0
        var stale = 0
        for (job in store.pendingJobs()) {
            val target = store.findTarget(job.targetKind, job.targetId)
            if (target == null) {
                // 记录已不存在（被永久删除）：确保不会有残留闹钟
                alarms.cancel(job.targetKind, job.targetId)
                store.markApplied(job.targetKind, job.targetId)
                canceled++
                continue
            }
            if (target.revision != job.expectedRevision) {
                // 目标在登记之后又被改过：这条期望已过期，丢弃它。
                // 注意"丢弃"是指不碰平台——更新的那条期望会来做正确的事。
                stale++
                continue
            }
            val at = target.remindAt
            if (at == null || at <= System.currentTimeMillis()) {
                alarms.cancel(job.targetKind, job.targetId)
                canceled++
            } else {
                alarms.schedule(job.targetKind, job.targetId, at)
                applied++
            }
            store.markApplied(job.targetKind, job.targetId)
        }
        return Summary(applied, canceled, stale)
    }

    /**
     * 全量对齐。启动与恢复之后调用：
     * 1) 取消所有登记过的目标（含已经不该存在的）；
     * 2) 按数据库现状重新登记期望并立即应用。
     */
    suspend fun reconcileAll(): Summary {
        val now = System.currentTimeMillis()
        store.knownTargets().forEach { (kind, id) -> alarms.cancel(kind, id) }

        var scheduled = 0
        var canceled = 0
        for (target in store.desiredTargets()) {
            val at = target.remindAt
            if (at != null && at > now) {
                alarms.schedule(target.kind, target.id, at)
                scheduled++
            } else {
                alarms.cancel(target.kind, target.id)
                canceled++
            }
            store.upsertJob(target.kind, target.id, target.revision, at)
            store.markApplied(target.kind, target.id)
        }
        return Summary(scheduled, canceled, staleSkipped = 0)
    }
}
