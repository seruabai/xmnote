package com.purenote.local.notify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规范 §9 / §16：提醒协调。
 * 最关键的一条是「旧任务不能覆盖新提醒」——过期期望必须被丢弃且**不许碰平台闹钟**。
 * 协调逻辑与 Android 解耦，因此可以在这里直接测。
 */
class ReminderReconcilerTest {

    private class FakeStore(
        var jobs: MutableList<ReminderJob> = mutableListOf(),
        var targets: MutableList<ReminderTarget> = mutableListOf(),
        var known: MutableList<Pair<String, Long>> = mutableListOf(),
    ) : ReminderStore {
        val applied = mutableListOf<Pair<String, Long>>()
        override suspend fun pendingJobs() = jobs.toList()
        override suspend fun knownTargets() = known.toList()
        override suspend fun desiredTargets() = targets.toList()
        override suspend fun findTarget(kind: String, targetId: Long) =
            targets.firstOrNull { it.kind == kind && it.id == targetId }
        override suspend fun upsertJob(kind: String, targetId: Long, revision: Long, remindAt: Long?) {
            known += kind to targetId
        }
        override suspend fun markApplied(kind: String, targetId: Long) {
            applied += kind to targetId
            jobs.removeAll { it.targetKind == kind && it.targetId == targetId }
        }
    }

    private class FakeSink : AlarmSink {
        val scheduled = mutableListOf<Triple<String, Long, Long>>()
        val canceled = mutableListOf<Pair<String, Long>>()
        override fun schedule(kind: String, targetId: Long, at: Long) { scheduled += Triple(kind, targetId, at) }
        override fun cancel(kind: String, targetId: Long) { canceled += kind to targetId }
    }

    private val future = System.currentTimeMillis() + 3_600_000L
    private val past = System.currentTimeMillis() - 3_600_000L

    private fun job(kind: String = "note", id: Long = 1, rev: Long = 7, at: Long? = future) =
        ReminderJob(kind, id, rev, at)

    private fun target(kind: String = "note", id: Long = 1, rev: Long = 7, at: Long? = future) =
        ReminderTarget(kind, id, rev, at)

    @Test
    fun appliesAPendingJobWhenTheRevisionStillMatches() = runBlocking {
        val store = FakeStore(jobs = mutableListOf(job()), targets = mutableListOf(target()))
        val sink = FakeSink()
        val summary = ReminderReconciler(store, sink).applyPending()

        assertEquals(1, sink.scheduled.size)
        assertEquals(Triple("note", 1L, future), sink.scheduled.single())
        assertEquals(1, summary.applied)
        assertTrue("推送成功后必须标记已应用", store.applied.contains("note" to 1L))
    }

    @Test
    fun aStaleJobIsDroppedWithoutTouchingTheAlarm() = runBlocking {
        // 登记时期望 rev=7，但目标现在已经到了 rev=9（又被改过）。
        // 这条期望不能生效——否则"旧任务覆盖了新提醒"。
        val store = FakeStore(
            jobs = mutableListOf(job(rev = 7)),
            targets = mutableListOf(target(rev = 9, at = future + 1000)),
        )
        val sink = FakeSink()
        val summary = ReminderReconciler(store, sink).applyPending()

        assertTrue("过期期望绝不能碰平台闹钟", sink.scheduled.isEmpty())
        assertTrue("也不能取消——新提醒还在等着被推送", sink.canceled.isEmpty())
        assertEquals(1, summary.staleSkipped)
        assertEquals("过期期望不得被标记为已应用", 0, store.applied.size)
    }

    @Test
    fun aJobWhoseReminderWasClearedCancelsTheAlarm() = runBlocking {
        val store = FakeStore(jobs = mutableListOf(job()), targets = mutableListOf(target(at = null)))
        val sink = FakeSink()
        ReminderReconciler(store, sink).applyPending()

        assertEquals(listOf("note" to 1L), sink.canceled)
        assertTrue(sink.scheduled.isEmpty())
    }

    @Test
    fun aJobForADeletedTargetCancelsTheAlarm() = runBlocking {
        val store = FakeStore(jobs = mutableListOf(job()), targets = mutableListOf())
        val sink = FakeSink()
        ReminderReconciler(store, sink).applyPending()

        assertEquals("目标没了，残留闹钟必须被取消", listOf("note" to 1L), sink.canceled)
        assertTrue(sink.scheduled.isEmpty())
    }

    @Test
    fun aReminderTimeAlreadyInThePastIsNotScheduled() = runBlocking {
        val store = FakeStore(jobs = mutableListOf(job()), targets = mutableListOf(target(at = past)))
        val sink = FakeSink()
        ReminderReconciler(store, sink).applyPending()

        assertTrue("过期时间不该再排闹钟", sink.scheduled.isEmpty())
        assertEquals(listOf("note" to 1L), sink.canceled)
    }

    @Test
    fun reconcileAllCancelsKnownTargetsBeforeRebuilding() = runBlocking {
        // 启动/恢复后的全量对齐：先取消所有登记过的目标，再按数据库现状重建。
        // 只补不删的话，换机恢复后残留的闹钟会在错误时间弹出来。
        val store = FakeStore(
            targets = mutableListOf(target(id = 1, at = future), target(id = 2, at = null)),
            known = mutableListOf("note" to 1L, "note" to 2L, "note" to 99L),
        )
        val sink = FakeSink()
        val summary = ReminderReconciler(store, sink).reconcileAll()

        // 断言的是"集合包含"而不是数量：重建阶段会对没有提醒时间的目标再取消一次，
        // 数量断言会把一个正确实现判成失败。
        listOf(1L, 2L, 99L).forEach {
            assertTrue("登记过的目标 $it 必须先取消", sink.canceled.contains("note" to it))
        }
        assertEquals("只有带未来提醒时间的才重建", 1, sink.scheduled.size)
        assertEquals(1L, sink.scheduled.single().second)
        assertEquals(1, summary.applied)
    }

    @Test
    fun reconcileAllIsIdempotent() = runBlocking {
        val store = FakeStore(
            targets = mutableListOf(target(id = 1, at = future)),
            known = mutableListOf("note" to 1L),
        )
        val sink = FakeSink()
        val reconciler = ReminderReconciler(store, sink)

        reconciler.reconcileAll()
        val scheduledAfterFirst = sink.scheduled.toList()
        val canceledAfterFirst = sink.canceled.toSet()

        reconciler.reconcileAll()
        // 幂等的判据是"结果状态一致"，不是"调用次数不增加"——
        // 全量对齐本来就会再取消、再排一次，重复执行不该产生新的目标集合。
        // 注意不能用 list.minus(collection)：Kotlin 的 minus 是"去掉所有匹配元素"，
        // [A,A] - [A] == []，会把正确实现判成失败。按位置切分才是想要的语义。
        val secondRound = sink.scheduled.drop(scheduledAfterFirst.size)
        assertEquals("第二轮也应该把同一个目标排上", scheduledAfterFirst.toSet(), secondRound.toSet())
        assertEquals("只应有一个目标被排期", 1, scheduledAfterFirst.size)
        assertEquals("第二轮取消的第一个目标与第一轮相同", canceledAfterFirst, sink.canceled.drop(1).take(1).toSet())
    }
}
