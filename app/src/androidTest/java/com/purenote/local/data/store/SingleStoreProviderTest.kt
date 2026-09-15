package com.purenote.local.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 规范 §6.1：**全应用只有一个数据库提供者**。
 * Activity / ViewModel / 悬浮服务 / 提醒 Receiver 都不得自行构造数据层。
 *
 * 这条用例来自一个真实故障：StoreControl 原先用的是**实例级**锁，
 * 而 NoteRepository 在 3 处被构造（Application + ReminderReceiver ×2），
 * 每处各建一个 StoreControl。两个实例同时看到"没有指针"，各自生成一个 epoch
 * 并各写一次 active.json —— 磁盘上出现**两个库**，写进 A 的数据在 B 里看不见，
 * 而且从界面上完全看不出来（两边都"能正常读写自己那一个"）。
 */
@RunWith(AndroidJUnit4::class)
class SingleStoreProviderTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // 必须先丢弃上一轮缓存，否则测的是别的用例留下的实例
        DatabaseProvider.resetForTests()
    }

    @After
    fun tearDown() {
        // 只清掉**本用例自己**建的那个库。
        // 早先这里把 databases/ 下所有文件都删了，结果把同时运行的
        // SavePerformanceBaselineTest 的 10,000 条数据集也一起删掉，
        // 导致后者按 seeded 标记跳过重新灌数据、在空库上量出错误的耗时。
        val active = runCatching { StoreControl(ctx).activePointer().dbName }.getOrNull()
        DatabaseProvider.resetForTests()
        if (active != null) ctx.deleteDatabase(active)
        File(ctx.filesDir, "store-control").deleteRecursively()
    }

    @Test
    fun twoRepositoriesResolveToTheSameStoreEpoch() {
        val a = NoteRepository(ctx)
        val b = NoteRepository(ctx)
        assertEquals("两个仓库必须指向同一个存储代次", a.storeEpoch, b.storeEpoch)
        assertTrue("代次不能为空", a.storeEpoch.isNotBlank())
    }

    @Test
    fun aWriteThroughOneRepositoryIsVisibleThroughAnother() {
        val a = NoteRepository(ctx)
        val b = NoteRepository(ctx)
        val id = runBlocking {
            a.createNote(NoteKind.TEXT, "分裂检测", "写进 A 的正文", emptyList(), emptyList(), 0, null)
        }
        val seen = runBlocking { b.getNote(id) }
        assertNotNull("写进 A 的笔记必须在 B 里可见（曾经会分裂成两个库）", seen)
        assertEquals("写进 A 的正文", seen!!.body)
    }

    @Test
    fun onlyOneDatabaseFileIsEverCreatedForAFreshInstall() {
        val a = NoteRepository(ctx)
        runBlocking { a.noteCount() }          // 触发建库
        val b = NoteRepository(ctx)
        runBlocking { b.noteCount() }

        val dir = ctx.getDatabasePath("x").parentFile!!
        val dbs = dir.listFiles { f -> f.isFile && f.name.endsWith(".db") } ?: emptyArray()
        assertEquals(
            "全新安装只能有一个库文件，实际=${dbs.map { it.name }}",
            1,
            dbs.size,
        )
    }
}
