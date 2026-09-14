package com.purenote.local.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 规范 §14：发布顺序、状态记录、固定与轮换。用临时目录在 JVM 上测。 */
class LocalBackupStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = LocalBackupStore(tmp.root)
    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun publishAt(s: LocalBackupStore, at: Long, kind: BackupKind = BackupKind.AUTO) {
        val staged = s.newStagingFile(at)
        staged.writeBytes(ByteArray(64))
        s.publish(staged, kind, at)
    }

    @Test
    fun publishMovesTheStagedFileIntoPlace() {
        val s = store()
        val staged = s.newStagingFile(now)
        staged.writeBytes(ByteArray(32) { 1 })
        val published = s.publish(staged, BackupKind.AUTO, now)

        assertTrue(published.exists())
        assertFalse("暂存文件必须已被搬走", staged.exists())
        assertEquals(32L, published.length())
        assertEquals(1, s.list().size)
    }

    @Test
    fun listReportsKindAndTimestampFromTheName() {
        val s = store()
        publishAt(s, now, BackupKind.AUTO)
        publishAt(s, now + 1, BackupKind.MANUAL)
        publishAt(s, now + 2, BackupKind.PRE_MIGRATION)

        val byKind = s.list().groupBy { it.kind }
        assertEquals(1, byKind[BackupKind.AUTO]?.size)
        assertEquals(1, byKind[BackupKind.MANUAL]?.size)
        assertEquals(1, byKind[BackupKind.PRE_MIGRATION]?.size)
        assertEquals(now + 1, byKind[BackupKind.MANUAL]!!.single().createdAt)
    }

    @Test
    fun pinnedBackupsSurviveRotation() {
        val s = store()
        // 近期备份填满档位（备份太少时每个桶都成为代表，什么都删不掉）
        repeat(60) { d -> publishAt(s, now - d * day) }
        // 两份同一月桶内的古老自动备份：较新的那份会被选为该月的代表
        publishAt(s, now - 800 * day)          // 较新 -> 月代表
        publishAt(s, now - 801 * day)          // 较旧 -> 无档位保护
        val pinned = s.list().maxByOrNull { it.createdAt }!!   // 即 now - 800*day 那份
        s.pin(pinned.name)

        val deletedBefore = s.list().size
        val deleted = s.rotate(now)

        assertTrue("应有文件被轮换（否则这个用例什么也没验证）", deleted.isNotEmpty())
        assertTrue("被固定的那份必须留着", s.list().any { it.name == pinned.name })
        assertTrue("删掉的确实更少", s.list().size < deletedBefore)
        assertTrue(
            "无档位保护的那一份必须被删掉",
            deleted.map { it.name }.contains("auto-${now - 801 * day}.purenote.zip"),
        )
    }

    @Test
    fun rotationIsPausedWhileAnomalyIsDetected() {
        val s = store()
        publishAt(s, now - 400 * day)
        s.writeState(s.readState().copy(rotationPaused = true))

        val deleted = s.rotate(now)
        assertTrue("暂停期间不得删除任何备份", deleted.isEmpty())
        assertEquals(1, s.list().size)
    }

    @Test
    fun stateRoundTripsIncludingPinnedNames() {
        val s = store()
        val state = LocalBackupState(
            lastSuccessAt = 123L, lastNoteCount = 42, rotationPaused = true,
            pinnedNames = listOf("auto-1.purenote.zip"), lastContentStamp = 999L,
        )
        s.writeState(state)
        assertEquals(state, LocalBackupStore(tmp.root).readState())
    }

    @Test
    fun missingStateFileYieldsDefaultsRatherThanCrashing() {
        val state = LocalBackupStore(tmp.root).readState()
        assertEquals(0L, state.lastSuccessAt)
        assertEquals(-1, state.lastNoteCount)
        assertFalse(state.rotationPaused)
    }

    @Test
    fun cleanupStagingRemovesHalfWrittenTemporaries() {
        val s = store()
        s.newStagingFile(now).writeBytes(ByteArray(8))
        assertEquals(1, s.listStagingCount())
        s.cleanupStaging()
        assertEquals(0, s.listStagingCount())
    }

    private fun LocalBackupStore.listStagingCount(): Int =
        java.io.File(tmp.root, "staging").listFiles()?.size ?: 0
}
