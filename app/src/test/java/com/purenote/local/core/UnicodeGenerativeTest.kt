package com.purenote.local.core

import com.purenote.local.data.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 规范 §16：「额外做 Unicode 生成式测试和固定随机种子的操作序列。」
 *
 * 用手写用例只能覆盖想到的情况；笔记正文是用户自由输入，
 * 变体几乎无限（emoji、代理对、组合字符、全角空格、零宽字符、控制字符……）。
 * 这里用**固定种子**生成大量样本，断言几条必须永远成立的性质。
 * 固定种子保证失败可复现。
 */
class UnicodeGenerativeTest {

    private val seed = 20260915L

    /** 构造"像笔记正文"的随机文本：混入所有已知会出问题的字符类别。 */
    private fun randomBody(random: Random, lines: Int): String {
        val pool = listOf(
            "中文", "abc", "123", "# ", "## ", "- [ ] ", "- [x] ", "- ", "1. ", "> ",
            "\u3000", "\u2005", "\u2060", "\uE201", "\uE202", "\uE203",
            "\u2610", "\u2611", "[img:x.jpg]", "![](y.jpg)", "&", "<", ">", "\"", "'",
            "\uD83D\uDE00", "\uD83E\uDD80", "\uD83C\uDF89", "\u0301", "\u200B", "\uFEFF",
            "\n", "\t", "\r", " ", "%", "_", "\\", "\uFFFD",
        )
        val sb = StringBuilder()
        repeat(lines) { i ->
            sb.append(pool[random.nextInt(pool.size)])
            if (i < lines - 1) sb.append('\n')
        }
        return sb.toString()
    }

    @Test
    fun migrationIsIdempotentForAnyGeneratedBody() {
        val random = Random(seed)
        repeat(3000) {
            val body = randomBody(random, random.nextInt(1, 6))
            val once = NoteMarkup.migrateBodyV1toV2(body)
            val twice = NoteMarkup.migrateBodyV1toV2(once)
            assertEquals("迁移必须幂等，输入=${body.replace("\n", "|")}", once, twice)
        }
    }

    @Test
    fun parsingNeverThrowsForAnyGeneratedBody() {
        val random = Random(seed + 1)
        repeat(3000) {
            val body = randomBody(random, random.nextInt(1, 6))
            // 这些函数在渲染路径上会遍历每一行，任何一个抛异常都会崩在首页卡片上
            body.split('\n').forEach { line ->
                NoteMarkup.tagInfo(line)
                NoteMarkup.headingLevel(line)
                NoteMarkup.withoutHeading(line)
                NoteMarkup.isImageLine(line)
                NoteMarkup.imageNameOf(line)
            }
            NoteMarkup.stripHeadingMarkers(body)
            NoteMarkup.previewText(body)
            NoteMarkup.imageNames(body)
        }
    }

    @Test
    fun trailingImageLineTerminationIsIdempotent() {
        val random = Random(seed + 2)
        repeat(3000) {
            val body = randomBody(random, random.nextInt(1, 5))
            val once = NoteMarkup.terminateTrailingImageLine(body)
            assertEquals("补尾换行必须幂等", once, NoteMarkup.terminateTrailingImageLine(once))
        }
    }

    @Test
    fun insertImageLineNeverLosesContent() {
        // 直接断言真正要保的性质：**原有内容一行都不能少，且顺序不变**。
        // 用长度差做断言太脆（补尾换行、空行就地替换都会改变长度），
        // 而 A0-3 的故障恰恰是"光标行之后的内容整段消失"。
        val random = Random(seed + 3)
        val tag = "![](x.jpg)"
        repeat(1500) {
            val lines = (0 until random.nextInt(2, 5)).map { i ->
                "L$i" + randomBody(random, 1).replace("\n", "")
            }
            val body = lines.joinToString("\n")
            for (cursor in listOf(0, body.length / 2, body.length - 1)) {
                val out = NoteMarkup.insertImageLineAtCursor(body, cursor, "x.jpg")
                assertTrue("插入结果必须包含标记", out.contains(tag))
                lines.forEach { original ->
                    assertTrue(
                        "原有内容被吞掉了：[$original] 不在 [${out.replace("\n", "|")}]（cursor=$cursor）",
                        out.contains(original),
                    )
                }
                val positions = lines.map { out.indexOf(it) }
                assertEquals("原有各行的相对顺序必须不变", positions.sorted(), positions)
            }
        }
    }

    @Test
    fun checklistCodecRoundTripsAnyGeneratedText() {
        val random = Random(seed + 4)
        repeat(1500) {
            val items = (0 until random.nextInt(1, 5)).map {
                ChecklistItem(randomBody(random, 1).replace("\n", " "), random.nextBoolean())
            }
            // 编码器会清洗文本（换行/分隔符 -> 空格，再 trim），空文本在解码时被丢弃。
            // 期望值必须按同样的规则算，否则测的是自己的误解。
            val expected = items
                .map { it.copy(text = it.text.replace("\n", " ").replace(ChecklistCodec.SEP, ' ').trim()) }
                .filter { it.text.isNotEmpty() }

            val decoded = ChecklistCodec.decode(ChecklistCodec.encode(items))
            assertEquals("条目数必须一致（空文本在解码时被丢弃）", expected.size, decoded.size)
            expected.zip(decoded).forEach { (before, after) ->
                assertEquals("文本必须逐字相同", before.text, after.text)
                assertEquals("完成状态必须保留", before.done, after.done)
            }
        }
    }

    @Test
    fun backupVerifierDigestIsStableForAnyBytes() {
        val random = Random(seed + 5)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(0, 256)) { random.nextInt(256).toByte() }
            val a = BackupDigestProbe.sha256(bytes)
            val b = BackupDigestProbe.sha256(bytes.copyOf())
            assertEquals("同一份字节必须得到同一个校验值", a, b)
            assertEquals(64, a.length)
        }
    }
}

/** 只是为了让上面的生成式断言能碰到校验逻辑，避免测试里直接依赖备份包格式。 */
private object BackupDigestProbe {
    fun sha256(bytes: ByteArray): String =
        com.purenote.local.backup.BackupVerifier.sha256Of(bytes)
}
