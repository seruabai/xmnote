package com.purenote.local.core

import com.purenote.local.ui.backspaceIntercept
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A0 阻断性缺陷的回归测试。
 * 每个用例对应一次真实故障（设备实测：首页崩溃 / 升级锁库 / 正文被吞）。
 */
class NoteMarkupRegressionTest {

    // ---- A0-1：序号超出 Int 上限不得抛异常 ----

    @Test
    fun bigLeadingNumberIsTreatedAsPlainTextNotCrash() {
        // 修复前：groupValues[1].toInt() 抛 NumberFormatException
        val info = NoteMarkup.tagInfo("13800138000. 张三")
        assertEquals(NoteMarkup.HeadTag.NONE, info.tag)
        assertEquals(0, info.tagLen)
    }

    @Test
    fun intMaxOverflowBoundariesDoNotCrash() {
        for (line in listOf(
            "2147483647. ok",
            "2147483648. overflow",
            "20260913120000. 会议",
            "99999999999999999999999999. 超出 Long",
        )) {
            NoteMarkup.tagInfo(line)   // 不得抛异常
        }
    }

    @Test
    fun normalOrderedListStillParses() {
        val info = NoteMarkup.tagInfo("1. 正常条目")
        assertEquals(NoteMarkup.HeadTag.NUMBER, info.tag)
        assertEquals(1, info.number)
        assertEquals("1. ".length, info.tagLen)
    }

    @Test
    fun overflowedInheritanceDoesNotOverflowToNegative() {
        // 修复前：Int.MAX_VALUE + 1 溢出为 "-2147483648. "（负数序号）
        assertEquals("1. ", NoteMarkup.inheritTagPrefix("2147483647. 末尾序号"))
    }

    @Test
    fun normalInheritanceStillIncrements() {
        assertEquals("4. ", NoteMarkup.inheritTagPrefix("3. 第三条"))
        assertEquals("- [ ] ", NoteMarkup.inheritTagPrefix("- [x] 已完成"))
        assertEquals("- ", NoteMarkup.inheritTagPrefix("- 无序项"))
    }

    // ---- A0-2：首页卡片与迁移路径不得因这类正文崩溃 ----

    @Test
    fun cardRenderingPathDoesNotThrow() {
        // NoteCard.TextCardBody 的调用链：stripHeadingMarkers → withoutHeading → tagInfo
        val body = "会议记录\n13800138000. 张三\n20260913120000. 会议"
        assertEquals(body, NoteMarkup.stripHeadingMarkers(body))
        NoteMarkup.previewText(body)
    }

    @Test
    fun migrationIsIdempotentAndSafeOnSuchBodies() {
        val body = "13800138000. 张三\n\uE202\u2610 待办"
        val once = NoteMarkup.migrateBodyV1toV2(body)   // 不得抛异常（onUpgrade 路径）
        assertEquals(once, NoteMarkup.migrateBodyV1toV2(once))
        assertTrue("标题+勾选应叠加迁移", once.contains("## - [ ] 待办"))
        assertTrue("超长序号行应原样保留", once.contains("13800138000. 张三"))
    }

    // ---- A0-3：插入图片不得吞掉后续内容 ----

    @Test
    fun insertImageLinePreservesEverythingAfterCursorLine() {
        val body = "第一行\n第二行\n第三行"
        val out = NoteMarkup.insertImageLineAtCursor(body, 1, "img_1.jpg")
        assertEquals("第一行\n![](img_1.jpg)\n第二行\n第三行", out)
    }

    @Test
    fun insertImageLineAtStartKeepsTail() {
        assertEquals(
            "A\n![](x.jpg)\nB\nC",
            NoteMarkup.insertImageLineAtCursor("A\nB\nC", 0, "x.jpg"),
        )
    }

    @Test
    fun insertImageLineOnLastLineKeepsPriorLines() {
        val body = "第一行\n第二行"
        assertEquals("第一行\n第二行\n![](z.jpg)\n", NoteMarkup.insertImageLineAtCursor(body, 5, "z.jpg"))
    }

    @Test
    fun insertImageLineOverBlankLineStillReplacesInPlace() {
        // 空行就地替换（这条修复前就是对的，锁住行为）
        assertEquals("A\n![](b.jpg)\nC", NoteMarkup.insertImageLineAtCursor("A\n\nC", 2, "b.jpg"))
    }

    @Test
    fun insertedImageLineIsRecognised() {
        val out = NoteMarkup.insertImageLineAtCursor("第一行\n第二行", 1, "img_9.jpg")
        assertTrue(NoteMarkup.imageNames(out).contains("img_9.jpg"))
        assertFalse(out.contains("第二行\n\n"))
    }
    // ---- A0-4：图片行原子性 / 标题标记 ----

    @Test
    fun trailingImageLineGetsTerminatingNewline() {
        assertEquals("abc\n![](x.jpg)\n", NoteMarkup.terminateTrailingImageLine("abc\n![](x.jpg)"))
        assertEquals("![](x.jpg)\n", NoteMarkup.terminateTrailingImageLine("![](x.jpg)"))
        // 已有尾换行 / 末行不是图片行 → 原样返回
        assertEquals("abc\n![](x.jpg)\n", NoteMarkup.terminateTrailingImageLine("abc\n![](x.jpg)\n"))
        assertEquals("abc\ntext", NoteMarkup.terminateTrailingImageLine("abc\ntext"))
        assertEquals("", NoteMarkup.terminateTrailingImageLine(""))
    }

    @Test
    fun appendingToImageLineSplitsAfterTheMarkerNotInsideIt() {
        // 修复前：indexOf(']') = 2 → "![]\n(img_1.jpg)c"（标记被切碎，图片永久丢失）
        // cursorAfter = 插入字符之后的偏移（'c' 位于下标 18，故为 19）
        val (patched, cursor) = NoteMarkup.imageLineAppendIntercept("abc\n![](img_1.jpg)c", 19)!!
        assertEquals("abc\n![](img_1.jpg)\nc", patched)
        assertTrue(NoteMarkup.imageNames(patched).contains("img_1.jpg"))
        assertEquals(20, cursor)
    }

    @Test
    fun splitUsesTheFirstParenAfterThePrefixNotTheLastOne() {
        // 追加内容自身含 ')' 时，标记结束位置仍是 IMG_PREFIX 之后的第一个 ')'
        val (patched, _) = NoteMarkup.imageLineAppendIntercept("![](a.jpg))x", 12)!!
        assertEquals("![](a.jpg)\n)x", patched)
        assertEquals(listOf("a.jpg"), NoteMarkup.imageNames(patched))
    }

    @Test
    fun multiCharacterAppendIsAlsoRepaired() {
        // 粘贴 / 输入法一次上屏多个字符：光标落在追加段末尾
        val (patched, cursor) = NoteMarkup.imageLineAppendIntercept("![](a.jpg)xy", 12)!!
        assertEquals("![](a.jpg)\nxy", patched)
        assertEquals(13, cursor)
    }

    @Test
    fun intactImageLineNeedsNoRepair() {
        assertEquals(null, NoteMarkup.imageLineAppendIntercept("abc\n![](a.jpg)", 13))
    }

    @Test
    fun backspaceRemovesWholeHeadingMarker() {
        // 修复前：默认退格只删掉 "# " 里的空格 → "#标题"，标题样式静默丢失
        // 光标位于内容起点（"# " 之后），退格删的是标记末字符
        assertEquals("标题" to 0, backspaceIntercept("# 标题", 1))
        assertEquals("标题" to 0, backspaceIntercept("## 标题", 2))
        assertEquals("标题" to 0, backspaceIntercept("### 标题", 3))
    }

    @Test
    fun backspaceOnTagLineStillKeepsHeadingAndRemovesOnlyTheTag() {
        assertEquals("# x" to 2, backspaceIntercept("# - [ ] x", 7))
    }

    @Test
    fun backspaceOnPlainLineIsNotIntercepted() {
        assertEquals(null, backspaceIntercept("普通文本", 1))
    }
}
