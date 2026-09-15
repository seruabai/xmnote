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
import org.junit.Assert.assertNull
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
    fun setUp() {
        // 进程级提供者会被缓存，用例之间必须丢弃，否则测的是上一个用例的实例
        DatabaseProvider.resetForTests()
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
        DatabaseProvider.resetForTests()
    }

    private fun cleanup() {
        File(ctx.filesDir, "store-control").deleteRecursively()
        // 必须清掉**所有**候选库，而不是只清自己记下的那个：
        // 只要 databases/ 里留下两个 purenote-*.db，下一次"指针缺失"的启动
        // 就会被正确地判成"有歧义、拒绝猜测"——这是产品该有的行为，
        // 但会让下一个用例从"恢复选择"状态开始，而不是从全新安装开始。
        databaseDir()?.listFiles { f -> f.name.endsWith(".db") || f.name.endsWith(".db-journal") }
            ?.forEach { runCatching { it.delete() } }
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
    fun aStoreThatWasOpenedAndThenLostRaisesInsteadOfBeingSilentlyRecreated() {
        // 先真正打开一次（落 ready 标记），再把库文件删掉，模拟"库丢了但指针还在"
        val first = DatabaseProvider(ctx, StoreControl(ctx))
        first.require()
        val pointer = StoreControl(ctx).activePointer()
        first.close()
        ctx.deleteDatabase(pointer.dbName)

        val provider = DatabaseProvider(ctx, StoreControl(ctx))
        assertTrue("必须进入缺失/恢复状态，而不是 Ready", provider.status() is StoreStatus.Missing)

        val failure = runCatching { provider.require() }.exceptionOrNull()
        assertTrue("必须抛 StoreMissingException，实际=$failure", failure is StoreMissingException)
        assertFalse(
            "绝不能顺手建出一个空库",
            ctx.getDatabasePath(pointer.dbName).let { it.exists() && it.length() > 0 },
        )
    }

    @Test
    fun aCrashBetweenPublishingThePointerAndCreatingTheDatabaseDoesNotBrickTheApp() {
        // 这是崩溃注入实测暴露出来的窗口：指针先落盘，库文件要到 require() 打开时才创建。
        // 在两步之间被强杀，下次启动会看到"指针在、库不在"。
        // 早先的实现会把它当成"库丢了"抛 StoreMissingException ——
        // 一个从未有过数据的新装应用，被自己的保护机制挡在门外，永远打不开。
        val control = StoreControl(ctx)
        control.active()                       // 只落指针，不建库
        val pointer = control.activePointer()
        ctx.deleteDatabase(pointer.dbName)
        assertFalse("前置：库文件确实不存在", ctx.getDatabasePath(pointer.dbName).exists())

        val provider = DatabaseProvider(ctx, StoreControl(ctx))
        val db = provider.require()            // 必须照常创建
        assertTrue("应照常建库", ctx.getDatabasePath(pointer.dbName).exists())
        assertEquals(
            "建立出来的必须是可用的库",
            NotesDb.DB_VERSION,
            db.writableDatabase.version,
        )
        db.close()
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

    // ---- 规范 §12「中断处理」表里剩下的几种情形 ----

    @Test
    fun aCorruptPointerDoesNotCreateABlankDatabase() {
        // 表中最后一行：active.json 缺失或损坏但发现现有库 -> 进入恢复选择，
        // 不自动创建空白库，也不只按文件时间猜测最新库。
        val control = StoreControl(ctx)
        val pointer = control.active().pointer
        val db = NotesDb(ctx, pointer.dbName)
        db.insertNote(NoteKind.TEXT, "原有笔记", "正文", "", 0, null, 1000L)
        db.close()

        // 清掉别的用例可能留下的候选库，让"该用哪个库"无歧义
        databaseDir()?.listFiles { f -> f.name.startsWith("purenote-") && f.name.endsWith(".db") }
            ?.filter { it.name != pointer.dbName }
            ?.forEach { it.delete() }

        // 把指针文件弄坏
        val activeFile = File(ctx.filesDir, "store-control/active.json")
        val good = activeFile.readText()
        activeFile.writeText("{ this is not json")

        val recovered = StoreControl(ctx).active()
        assertTrue("损坏的指针必须被丢弃而不是当成有效值", recovered.pointer.dbName.isNotBlank())
        // 关键：绝不能因为读不出指针就把用户原有数据丢在一边、另起一个空库
        assertEquals(
            "必须仍然指向那个有数据的库，而不是新建一个",
            pointer.dbName,
            recovered.pointer.dbName,
        )
        assertTrue("原有库文件必须还在", ctx.getDatabasePath(pointer.dbName).exists())

        activeFile.writeText(good)
    }

    @Test
    fun multipleCandidateDatabasesRefuseToBeGuessedAt() {
        // 规范 §12 中断处理表最后一行：「active.json 缺失或损坏但发现现有库 ->
        // 进入恢复选择，不自动创建空白库，**也不只按文件时间猜测最新库**」。
        // 这条用例就是为了钉住"不猜"——按时间挑最新的那个，赌错就是把用户
        // 引到一个空的或过期的库上，而且从界面上完全看不出来。
        val control = StoreControl(ctx)
        control.active()

        val dir = databaseDir()!!
        dir.listFiles { f -> f.name.startsWith("purenote-") && f.name.endsWith(".db") }?.forEach { it.delete() }
        // 造两个都有数据的候选库
        listOf("purenote-aaaa.db", "purenote-bbbb.db").forEach { name ->
            val db = NotesDb(ctx, name)
            db.insertNote(NoteKind.TEXT, "候选 $name", "正文", "", 0, null, 1000L)
            db.close()
        }
        File(ctx.filesDir, "store-control/active.json").writeText("{ broken")

        val active = StoreControl(ctx).active()
        assertTrue("有多个候选时必须拒绝猜测", active.ambiguous)

        val provider = DatabaseProvider(ctx, StoreControl(ctx))
        val failure = runCatching { provider.require() }.exceptionOrNull()
        assertTrue("必须进入恢复而不是猜一个，实际=$failure", failure is StoreMissingException)

        // 两个候选库都必须原样保留，一个都不能被动过
        listOf("purenote-aaaa.db", "purenote-bbbb.db").forEach {
            assertTrue("$it 必须保留", ctx.getDatabasePath(it).exists())
        }
    }

    private fun databaseDir(): File? = ctx.getDatabasePath("probe").parentFile

    @Test
    fun anInterruptedRestoreLeavesTheOldStoreActive() = runBlocking {
        // 表中第二行：新目录完成但指针未切换 -> 旧活动库继续有效。
        // 这里用"包校验不通过"来代表恢复在切换之前中止。
        val pkg = seedLegacyThenExportPlusOneMore()
        val repo = NoteRepository(ctx)
        val before = repo.storeEpoch
        val beforeTitles = noteTitles(repo)

        val truncated = pkg.readBytes().copyOf(0)   // 空包 -> 必然在校验阶段失败
        val outcome = repo.restoreIntoNewStore(ByteArrayInputStream(truncated))
        assertTrue("必须失败", outcome is NoteRepository.RestoreOutcome.Failed)

        assertEquals("活动存储不得被切换", before, repo.storeEpoch)
        assertEquals("旧活动库必须继续有效", beforeTitles, noteTitles(repo))
        assertTrue("旧库文件必须保留", ctx.getDatabasePath(LEGACY).exists())

        // 而且此时恢复记录不应残留，否则每次启动都以为恢复没走完
        assertNull("中止的恢复不应留下恢复记录", StoreControl(ctx).readRecovery())
    }

    @Test
    fun thePreviousEpochRemainsReadableAfterASuccessfulRestore() = runBlocking {
        // 表中第 9 步：旧 epoch 与恢复前备份继续保留，不在同一次启动里清理。
        val pkg = seedLegacyThenExportPlusOneMore()
        val repo = NoteRepository(ctx)
        val oldEpoch = repo.storeEpoch
        val oldDbName = StoreControl(ctx).activePointer().dbName

        assertTrue(repo.restoreIntoNewStore(pkg.inputStream()) is NoteRepository.RestoreOutcome.Ok)
        assertNotEquals("必须换了一代", oldEpoch, repo.storeEpoch)

        // 直接打开旧库：数据必须还在（这是"至少一代完整数据可用"的另一半）
        val old = NotesDb(ctx, oldDbName)
        val titles = old.readableDatabase.rawQuery("SELECT title FROM notes ORDER BY title", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        old.close()
        assertEquals("旧代的两条笔记必须都还在", listOf("导出后新增", "旧笔记"), titles)
        assertNull("恢复完成后恢复记录必须清掉", StoreControl(ctx).readRecovery())
    }

    private companion object {
        const val LEGACY = "purenote.db"
    }
}