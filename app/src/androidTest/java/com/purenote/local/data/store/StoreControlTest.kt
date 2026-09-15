package com.purenote.local.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.data.NotesDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 规范 §12：活动存储指针。
 * 最关键的一条是"接管既有库"——老用户的数据库已经在 databases/purenote.db，
 * 首次运行若直接生成新 epoch 的名字，App 会打开空库，表现为"升级后笔记全没了"。
 */
@RunWith(AndroidJUnit4::class)
class StoreControlTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var control: StoreControl

    @Before
    fun setUp() {
        // 进程级提供者会被缓存，用例之间必须丢弃，否则测的是上一个用例的实例
        DatabaseProvider.resetForTests()
        cleanup()
        control = StoreControl(ctx)
    }

    @After
    fun tearDown() {
        cleanup()
        DatabaseProvider.resetForTests()
    }

    private fun cleanup() {
        File(ctx.filesDir, "store-control").deleteRecursively()
        listOf(LEGACY, EXTRA).forEach { ctx.deleteDatabase(it) }
    }

    @Test
    fun adoptsAnExistingLegacyDatabaseInsteadOfCreatingAnEmptyOne() {
        // 前置：模拟老用户，databases/purenote.db 已经有内容
        NotesDb(ctx, LEGACY).use { it.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS probe(x INTEGER)") }
        assertTrue(ctx.getDatabasePath(LEGACY).exists())

        val active = control.active()

        assertTrue("必须标记为首次初始化", active.justInitialized)
        assertEquals("必须接管既有库，而不是另起炉灶", LEGACY, active.pointer.dbName)
    }

    @Test
    fun firstRunWithoutLegacyDatabaseGeneratesAnEpochScopedName() {
        val active = control.active()
        assertTrue(active.justInitialized)
        assertNotEquals("没有旧库时不应沿用 legacy 名字", LEGACY, active.pointer.dbName)
        assertTrue(active.pointer.dbName.contains(active.pointer.epoch))
    }

    @Test
    fun pointerSurvivesAReopenAndIsNoLongerMarkedAsFirstRun() {
        val first = control.active()
        val second = StoreControl(ctx).active()
        assertFalse("第二次读取不应再标记为首次初始化", second.justInitialized)
        assertEquals(first.pointer.epoch, second.pointer.epoch)
        assertEquals(first.pointer.dbName, second.pointer.dbName)
    }

    @Test
    fun publishReplacesThePointer() {
        control.active()
        val pointer = StorePointer(epoch = "epoch-2", dbName = control.dbNameFor("epoch-2"), libraryId = "lib-2")
        control.publish(pointer)

        val read = StoreControl(ctx).active()
        assertEquals("epoch-2", read.pointer.epoch)
        assertEquals("lib-2", read.pointer.libraryId)
    }

    @Test
    fun recoveryRecordTracksAPreparedRestoreAndCanBeCleared() {
        assertNull("初始不应有恢复记录", control.readRecovery())

        control.markRecoveryPrepared(fromEpoch = "old", toEpoch = "new")
        val record = control.readRecovery()
        assertEquals(RecoveryRecord.PREPARED, record?.state)
        assertEquals("old", record?.fromEpoch)
        assertEquals("new", record?.toEpoch)

        control.clearRecovery()
        assertNull("完成后必须清掉，否则每次启动都以为恢复没走完", control.readRecovery())
    }

    @Test
    fun corruptPointerFileIsTreatedAsFirstRunRatherThanCrashing() {
        File(ctx.filesDir, "store-control").mkdirs()
        File(ctx.filesDir, "store-control/active.json").writeText("{ this is not json")

        val active = control.active()
        // 关键：不能崩、也不能把损坏的指针当成有效指针
        assertTrue(active.pointer.dbName.isNotBlank())
        assertTrue(active.justInitialized)
    }

    private companion object {
        const val LEGACY = "purenote.db"
        const val EXTRA = "purenote-extra.db"
    }
}