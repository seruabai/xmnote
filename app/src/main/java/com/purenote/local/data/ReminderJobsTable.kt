package com.purenote.local.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.purenote.local.notify.ReminderJob

/**
 * `reminder_jobs` 表的读写（规范 §9）。
 *
 * 这张表的用途：把"期望的提醒状态"与业务数据放在**同一个事务**里落库，
 * 之后再由协调器按期望去对齐系统闹钟。
 * 原来的做法是保存成功后在 ViewModel 里直接调 AlarmManager——
 * 保存失败时提醒照样被重排，等于把系统状态改成了与数据不一致的样子。
 */
internal object ReminderJobsTable {

    fun upsert(
        db: SQLiteDatabase,
        kind: String,
        targetId: Long,
        expectedRevision: Long,
        desiredAt: Long?,
        now: Long,
    ) {
        db.insertWithOnConflict(
            "reminder_jobs",
            null,
            ContentValues().apply {
                put("target_kind", kind)
                put("target_id", targetId)
                put("expected_revision", expectedRevision)
                put("desired_at", desiredAt)
                // 每次登记都回到 pending：这是"待推送"的唯一入口
                put("state", ReminderJob.STATE_PENDING)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun pending(db: SQLiteDatabase): List<ReminderJob> =
        db.rawQuery(
            "SELECT target_kind, target_id, expected_revision, desired_at, state " +
                "FROM reminder_jobs WHERE state = ? ORDER BY updated_at",
            arrayOf(ReminderJob.STATE_PENDING),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ReminderJob(
                            targetKind = c.getString(0),
                            targetId = c.getLong(1),
                            expectedRevision = c.getLong(2),
                            desiredAt = if (c.isNull(3)) null else c.getLong(3),
                            state = c.getString(4),
                        ),
                    )
                }
            }
        }

    fun knownTargets(db: SQLiteDatabase): List<Pair<String, Long>> =
        db.rawQuery("SELECT target_kind, target_id FROM reminder_jobs", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
        }

    fun markApplied(db: SQLiteDatabase, kind: String, targetId: Long, now: Long) {
        db.update(
            "reminder_jobs",
            ContentValues().apply {
                put("state", ReminderJob.STATE_APPLIED)
                put("updated_at", now)
            },
            "target_kind = ? AND target_id = ?",
            arrayOf(kind, targetId.toString()),
        )
    }
}
