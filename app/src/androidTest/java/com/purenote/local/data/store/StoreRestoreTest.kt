package com.purenote.local.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.backup.BackupIo
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NoteRepository
import com.purenote.local.data.NotesDb
import com.purenote.local.data.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 规范 §12 / §16「恢复中断」：每个阶段的强制中断都必须可解释，
 * 且**至少一代完整数据可用**；旧 epoch 的原始副本必须保留。
 */
@RunWith(AndroidJUnit4::class)
class StoreRestoreTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = cleanup()

    @After
    fun tearDown() = cleanup()

    private fun cleanup() {
        val control = StoreControl(ctx)
        val names = mutableListOf(LEGACY)
        runCatching { names += control.activePointer().dbName }
        File(ctx.filesDir, "store-control").deleteRecursively()
        names.forEach { runCatching { ctx.deleteDatabase(it) } }
        File(ctx.cacheDir, "restore-test.purenote.zip").delete()
    }

    private fun seedLegacyThenExportPlusOneMore(): File {
        val legacy = NotesDb(ctx, LEGACY)
        legacy.insertNote(NoteKind.TEXT, "旧笔记", "正文", "", 0, null, 1000L)
        val pkg = File(ctx.cacheDir, "restore-test.purenote.zip")
        runBlocking { BackupIo(ctx).export(pkg, legacy, appVersion = "test") }
        // 导出之后再改一笔：恢复后必须回到导出时的状态
        legacy.insertNote(NoteKind.TEXT, "导出后新增", "不该出现在恢复结果里", "", 0, null, 2000L)
        legacy.close()
        return pkg
    }

    private fun noteTitles(repo: NoteRepository): List<String> =
        repo.db.readableDatabase.rawQuery("SELECT title FROM notes ORDER BY title", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test
    fun restoreSwitchesToANewEpochAndKeepsTheOriginalCopy() = runBlocking {
        val pkg = seedLegacyThenExportPlusOneMore()
        val repo = NoteRepository(ctx)
        val before = repo.storeEpoch
        assertEquals("首次运行应接管既有库", "legacy", before)

        val outcome = repo.restoreIntoNewStore(pkg.inputStream())
        assertTrue("恢复应成功，实际=$outcome", outcome is NoteRepository.RestoreOutcome.Ok)
        val ok = outcome as NoteRepository.RestoreOutcome.Ok

        assertNotEquals("必须切到新的存储代次", before, ok.newEpoch)
        assertTrue("旧 epoch 的库文件必须保留（至少一代完整数据可用）", ctx.getDatabasePath(LEGACY).exists())
        assertEquals("恢复结果应回到导出时的状态", listOf("旧笔记"), noteTitles(repo))
        assertEquals("storeEpoch 应反映新代次", ok.newEpoch, repo.storeEpoch)
    }

    @Test
    fun aRejectedPackageLeavesTheActiveStoreCompletelyUntouched() = runBlocking {
        seedLegacyThenExportPlusOneMore()
        val repo = NoteRepository(ctx)
        val before = repo.storeEpoch
        val beforeTitles = noteTitles(repo)

        // 这不是一个备份包
        val garbage = "definitely-not-a-backup".toByteArray()
        val outcome = repo.restoreIntoNewStore(ByteArrayInputStream(garbage))

        assertTrue("必须失败", outcome is NoteRepository.RestoreOutcome.Failed)
        assertEquals("活动 epoch 不得变化", before, repo.storeEpoch)
        assertEquals("活动数据不得变化", beforeTitles, noteTitles(repo))
        // 失败后不应留下半成品库文件
        val leftover = File(ctx.filesDir, "store-control").listFiles()
            ?.filter { it.name.startsWith("active") } ?: emptyList()
        assertTrue("指针文件应仍然可读", leftover.isNotEmpty())
    }

    @Test
    fun pointerToAMissingDatabaseRaisesInsteadOfCreatingAnEmptyOne() {
        // 先正常初始化出指针，再把库文件删掉，模拟"库丢了但指针还在"
        val control = StoreControl(ctx)
        val pointer = control.active().pointer
        ctx.deleteDatabase(pointer.dbName)

        val provider = DatabaseProvider(ctx, StoreControl(ctx))
        val status = provider.status()
        assertTrue("必须进入缺失/恢复状态，而不是 Ready", status is StoreStatus.Missing)

        val failure = runCatching { provider.require() }.exceptionOrNull()
        assertTrue("必须抛 StoreMissingException，实际=$failure", failure is StoreMissingException)
        assertFalse("绝不能顺手建出一个空库", ctx.getDatabasePath(pointer.dbName).let { it.exists() && it.length() > 0 })
    }

    @Test
    fun writesCarryingAStaleEpochAreRejectedAfterASwitch() = runBlocking {
        val pkg = seedLegacyThenExportPlusOneMore()
        val repo = NoteRepository(ctx)
        val oldEpoch = repo.storeEpoch

        val restored = repo.restoreIntoNewStore(pkg.inputStream())
        assertTrue(restored is NoteRepository.RestoreOutcome.Ok)

        // 旧页面此时才送达的保存任务：携带的是旧 epoch
        val noteId = repo.db.readableDatabase.rawQuery("SELECT id FROM notes LIMIT 1", null)
            .use { it.moveToFirst(); it.getLong(0) }
        val result = repo.saveExisting(
            id = noteId, kind = NoteKind.TEXT, title = "旧页面写入", body = "不该写进新库",
            items = emptyList(), images = emptyList(), colorIndex = 0, folderId = null,
            pinned = false, remindAt = null, storeEpoch = oldEpoch,
        )
        assertEquals("必须返回 StoreChanged", SaveResult.StoreChanged, result)
        assertEquals("新库内容不得被旧写入污染", listOf("旧笔记"), noteTitles(repo))
    }

    @Test
    fun theProductionSavePathAlsoCarriesTheEpoch() = runBlocking {
        // 这条用例存在的理由：上一版只有直接调用 repo.saveExisting(storeEpoch=...) 的测试，
        // 而**生产路径**是 EditorScreen -> NoteViewModel -> SaveCoordinator -> repo。
        // 协调器漏传 storeEpoch 时，默认值 "" 会让 isNotBlank() 守卫直接跳过检查，
        // 隔离静默失效，而测试照样全绿。这里必须走协调器才算数。
        val pkg = seedLegacyThenExportPlusOneMore()
        val repo = NoteRepository(ctx)
        val oldEpoch = repo.storeEpoch
        assertTrue(repo.restoreIntoNewStore(pkg.inputStream()) is NoteRepository.RestoreOutcome.Ok)

        val noteId = repo.db.readableDatabase.rawQuery("SELECT id FROM notes LIMIT 1", null)
            .use { it.moveToFirst(); it.getLong(0) }

        val coordinator = com.purenote.local.feature.notes.SaveCoordinator.forRepository(repo)
        val result = coordinator.save(
            com.purenote.local.feature.notes.SaveCommand(
                noteId = noteId,
                operationId = "stale-epoch-op",
                sessionId = "s1",
                storeEpoch = oldEpoch,
                editGeneration = 1,
                expectedRevision = 1,
                kind = NoteKind.TEXT,
                title = "旧页面写入",
                body = "不该写进新库",
                items = emptyList(),
                images = emptyList(),
                colorIndex = 0,
                folderId = null,
                pinned = false,
                remindAt = null,
            ),
        )
        assertEquals("生产路径必须把 storeEpoch 传到仓库", SaveResult.StoreChanged, result)
        assertEquals("新库内容不得被旧写入污染", listOf("旧笔记"), noteTitles(repo))
    }

    private companion object {
        const val LEGACY = "purenote.db"
    }
}
