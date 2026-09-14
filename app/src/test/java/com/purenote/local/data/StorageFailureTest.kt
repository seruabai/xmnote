package com.purenote.local.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 规范 §15：错误分类必须可判别，不能把所有异常混成一种。 */
class StorageFailureTest {

    @Test
    fun classifiesKnownSqliteFailures() {
        assertEquals(
            StorageFailure.NO_SPACE,
            StorageFailure.of(android.database.sqlite.SQLiteFullException("disk full")),
        )
        assertEquals(
            StorageFailure.LOCKED,
            StorageFailure.of(android.database.sqlite.SQLiteDatabaseLockedException("locked")),
        )
        assertEquals(
            StorageFailure.CORRUPTED,
            StorageFailure.of(android.database.sqlite.SQLiteDatabaseCorruptException("corrupt")),
        )
        assertEquals(StorageFailure.PERMISSION, StorageFailure.of(SecurityException("denied")))
    }

    @Test
    fun corruptionGateIsClassifiedAsCorrupted() {
        assertEquals(
            StorageFailure.CORRUPTED,
            StorageFailure.of(IllegalStateException("数据库处于损坏保全状态，已拒绝写入（现场已保留）：x")),
        )
    }

    @Test
    fun unknownFailuresAreNotSilentlySuccess() {
        assertEquals(StorageFailure.UNKNOWN, StorageFailure.of(RuntimeException("boom")))
    }
}
