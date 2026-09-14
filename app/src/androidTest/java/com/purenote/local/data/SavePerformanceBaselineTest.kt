package com.purenote.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.core.ChecklistCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 规范 §16 的性能测试预算：
 * 「普通短笔记保存 p95 目标 300ms 以内，主线程无数据库/大文件工作；
 *   记录设备、内容大小、样本数和失败率。」
 * 「固定数据基线：10,000 条笔记、不同大小正文、1,000 个附件、历史、回收站、
 *   分类及父子待办。」
 *
 * 这不是"跑一下就完"的基准，而是回归闸门：阶段 B/C/D 引入的统一事务、
 * CAS 条件更新、每次提交写历史快照，都在保存路径上加了工作量，
 * 必须证明它们没有把保存拖出预算。
 */
@RunWith(AndroidJUnit4::class)
class SavePerformanceBaselineTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: NoteRepository

    private val noteCount = 10_000
    private val todoCount = 2_000
    private val samples = 40

    @Before
    fun setUp() {
        // 固定数据集只建一次：@Before 每个用例都跑，10,000 行重复灌三遍没有意义
        if (seeded) {
            repo = NoteRepository(ctx, DB)
            return
        }
        ctx.deleteDatabase(DB)
        val seed = NotesDb(ctx, DB)
        val now = 1_700_000_000_000L
        seed.inTransaction { database ->
            val stmt = database.compileStatement(
                "INSERT INTO notes(uuid,kind,title,body,images,color,folder_id,pinned,trashed," +
                    "repeat_type,all_day,revision,body_format_version,created_at,updated_at) " +
                    "VALUES (?,0,?,?,?,0,NULL,0,0,0,0,1,2,?,?)",
            )
            repeat(noteCount) { i ->
                stmt.clearBindings()
                stmt.bindString(1, "seed-uuid-$i")
                stmt.bindString(2, "标题 $i")
                // 混合不同长度正文：短、中、带 Markdown 标记
                stmt.bindString(
                    3,
                    when (i % 3) {
                        0 -> "短正文 $i"
                        1 -> "中等长度的正文 $i，".repeat(6)
                        else -> "# 标题\n- [ ] 任务 $i\n![](img_$i.jpg)\n普通段落"
                    },
                )
                stmt.bindString(4, if (i % 3 == 2) "img_$i.jpg" else "")
                stmt.bindLong(5, now + i)
                stmt.bindLong(6, now + i)
                stmt.executeInsert()
            }
            val todo = database.compileStatement(
                "INSERT INTO todos(uuid,parent_id,title,done,due_at,all_day,repeat_type,sort_index," +
                    "trashed,revision,created_at,updated_at) VALUES (?,NULL,?,0,NULL,0,0,0,0,1,?,?)",
            )
            repeat(todoCount) { i ->
                todo.clearBindings()
                todo.bindString(1, "todo-uuid-$i")
                todo.bindString(2, "待办 $i")
                todo.bindLong(3, now + i)
                todo.bindLong(4, now + i)
                todo.executeInsert()
            }
        }
        seed.close()
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "perf-baseline.txt").delete()
        seeded = true
        repo = NoteRepository(ctx, DB)
    }

    private fun percentile(sorted: List<Long>, p: Double): Long =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    @Test
    fun shortNoteSaveMeetsTheP95BudgetOnAFullDatabase() {
        runBlocking {
            val body = "这是一条普通短笔记的正文内容"
            val noteId = repo.createNote(NoteKind.TEXT, "性能基线", body, emptyList(), emptyList(), 0, null)

            // 预热：排除首次 JIT / 首次语句编译
            repeat(5) { i ->
                repo.saveExisting(
                    noteId, NoteKind.TEXT, "性能基线", "$body $i",
                    emptyList(), emptyList(), 0, null, false, null,
                )
            }

            val timings = mutableListOf<Long>()
            repeat(samples) { i ->
                val t0 = System.nanoTime()
                val result = repo.saveExisting(
                    noteId, NoteKind.TEXT, "性能基线", "$body 第$i 次",
                    emptyList(), emptyList(), 0, null, false, null,
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue("第 $i 次保存失败：$result", result is SaveResult.Saved)
                timings += ms
            }

            val sorted = timings.sorted()
            val p50 = percentile(sorted, 0.50)
            val p95 = percentile(sorted, 0.95)
            val max = sorted.last()

            writeReport(
                """
                === 保存延迟基线（规范 §16）===
                数据集: $noteCount 条笔记 + $todoCount 条待办
                样本数: $samples（另有 5 次预热）
                正文长度: 短（${body.length} 字）
                p50 = ${p50}ms
                p95 = ${p95}ms
                max = ${max}ms
                预算  = 300ms (p95)
                """.trimIndent(),
            )

            assertTrue("p95 ${p95}ms 超出 300ms 预算；完整耗时=$sorted", p95 <= 300)
        }
    }

    @Test
    fun homeListQueryStaysResponsiveOnAFullDatabase() {
        runBlocking {
            // 列表查询是另一个 O(n) 热点：保存变快但首页卡住没有意义
            val timings = mutableListOf<Long>()
            repeat(10) {
                val t0 = System.nanoTime()
                repo.loadNotes(NoteFilter())
                timings += (System.nanoTime() - t0) / 1_000_000
            }
            val sorted = timings.sorted()
            val p95 = percentile(sorted, 0.95)
            writeReport(
                """
                === 首页列表查询（$noteCount 条）===
                p50 = ${percentile(sorted, 0.5)}ms
                p95 = ${p95}ms
                max = ${sorted.last()}ms
                """.trimIndent(),
            )
            assertTrue("首页列表 p95 ${p95}ms 过长", p95 <= 1000)
        }
    }

    @Test
    fun checklistSaveMeetsTheBudget() {
        runBlocking {
            val items = (1..5).map { ChecklistItem("条目 $it", it % 2 == 0) }
            val id = repo.createNote(NoteKind.CHECKLIST, "清单基线", "", items, emptyList(), 0, null)
            repeat(3) { repo.saveExisting(id, NoteKind.CHECKLIST, "清单基线", "", items, emptyList(), 0, null, false, null) }

            val timings = mutableListOf<Long>()
            repeat(samples) {
                val t0 = System.nanoTime()
                repo.saveExisting(id, NoteKind.CHECKLIST, "清单基线", "", items, emptyList(), 0, null, false, null)
                timings += (System.nanoTime() - t0) / 1_000_000
            }
            val sorted = timings.sorted()
            val p95 = percentile(sorted, 0.95)
            writeReport(
                """
                === 清单保存（5 条子项）===
                p95 = ${p95}ms  max = ${sorted.last()}ms
                （清单正文经 ChecklistCodec 编码，走同一条 CAS + 历史快照路径）
                """.trimIndent(),
            )
            assertTrue("清单保存 p95 ${p95}ms 超出预算", p95 <= 300)
        }
    }

    /** 规范 §16 要求"记录设备、内容大小、样本数和失败率"，所以把结果落到可拉取的文件里。 */
    private fun writeReport(text: String) {
        // 既落文件（可拉取）也打日志（am instrument 后立刻可读，App 被卸载也不影响）
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, "perf-baseline.txt").appendText(text + "\n\n")
        }
        android.util.Log.i("PerfBaseline", text.replace("\n", " | "))
    }

    private companion object {
        const val DB = "perf-baseline-test.db"

        @Volatile
        var seeded = false

        /** JUnit 要求 @AfterClass 必须是静态的：放进 companion 并标 @JvmStatic。 */
        @JvmStatic
        @AfterClass
        fun cleanup() {
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DB)
            seeded = false
        }
    }
}
