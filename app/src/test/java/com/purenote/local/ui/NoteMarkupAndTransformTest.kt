package com.purenote.local.ui

import com.purenote.local.core.NoteMarkup
import com.purenote.local.NoteTextSize
import com.purenote.local.ui.typeScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标记系统参数化测试：标签解析/切换/继承 + 图片行展开映射的不变量。
 * 这些测试在 JVM 上运行，映射类 bug（曾导致模拟器 ANR）应当在这里先失败。
 */
class NoteMarkupAndTransformTest {

    // ---------- tagInfo ----------

    @Test
    fun `tagInfo 识别各行首标签`() {
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, NoteMarkup.tagInfo("☐ 内容").tag)
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, NoteMarkup.tagInfo("☑ 内容").tag)
        assertEquals(NoteMarkup.HeadTag.BULLET, NoteMarkup.tagInfo("- 内容").tag)
        assertEquals(NoteMarkup.HeadTag.NUMBER, NoteMarkup.tagInfo("3. 内容").tag)
        assertEquals(3, NoteMarkup.tagInfo("3. 内容").number)
        assertEquals(3, NoteMarkup.tagInfo("3. 内容").tagLen)
        assertEquals(NoteMarkup.HeadTag.QUOTE, NoteMarkup.tagInfo("> 内容").tag)
        assertEquals(NoteMarkup.HeadTag.INDENT, NoteMarkup.tagInfo("　　内容").tag)
        assertEquals(NoteMarkup.HeadTag.NONE, NoteMarkup.tagInfo("普通内容").tag)
        // 与标题标记叠加
        val info = NoteMarkup.tagInfo("${NoteMarkup.H1}☐ 内容")
        assertEquals(1, info.headLen)
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, info.tag)
    }

    @Test
    fun `toggleHeadTag 增删与替换并返回内容起点`() {
        // 无 → 加
        var (line, offset) = NoteMarkup.toggleHeadTag("内容", NoteMarkup.HeadTag.BULLET)
        assertEquals("- 内容", line)
        assertEquals(2, offset)
        // 同种 → 移除
        val (removed, off2) = NoteMarkup.toggleHeadTag(line, NoteMarkup.HeadTag.BULLET)
        assertEquals("内容", removed)
        assertEquals(0, off2)
        // 换种：无序 → 有序
        val (num, off3) = NoteMarkup.toggleHeadTag("- 内容", NoteMarkup.HeadTag.NUMBER, numberForNew = 5)
        assertEquals("5. 内容", num)
        assertEquals(3, off3)
        // 带标题标记的行加勾选：标记保留，偏移含标题长度
        val (h, off4) = NoteMarkup.toggleHeadTag("${NoteMarkup.H1}标题", NoteMarkup.HeadTag.CHECKBOX)
        assertEquals("${NoteMarkup.H1}☐ 标题", h)
        assertEquals(3, off4)
    }

    @Test
    fun `inheritTagPrefix 回车继承与序号自增`() {
        assertEquals(NoteMarkup.BOX_UNCHECKED_PREFIX, NoteMarkup.inheritTagPrefix("☐ 买牛奶"))
        assertEquals("- ", NoteMarkup.inheritTagPrefix("- 已有条目"))
        assertEquals("3. ", NoteMarkup.inheritTagPrefix("2. 上一行"))
        assertEquals("> ", NoteMarkup.inheritTagPrefix("> 引用"))
        assertEquals(NoteMarkup.INDENT_PREFIX, NoteMarkup.inheritTagPrefix("　　缩进行"))
        assertNull(NoteMarkup.inheritTagPrefix("普通行"))
        assertNull(NoteMarkup.inheritTagPrefix("${NoteMarkup.H1}标题行"))
    }

    @Test
    fun `toggleTailIndent 行尾加减缩进`() {
        assertEquals("内容　　", NoteMarkup.toggleTailIndent("内容"))
        assertEquals("内容", NoteMarkup.toggleTailIndent("内容　　"))
    }

    // ---------- transformNoteText 映射不变量 ----------

    private val K = 3 // IMAGE_BLOCK_EXTRA_LINES

    @Test
    fun `无图片行时映射为恒等`() {
        val body = "第一行\n☐ 买牛奶\n普通行"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        for (i in 0..body.length) assertEquals(i, r.origToTrans[i])
        assertEquals(body.length, r.annotated.length)
        assertTrue(r.imageBlocks.isEmpty())
    }

    @Test
    fun `图片行展开后映射单调且端点正确`() {
        val body = "ab\n[img:t.png]\ncd"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        // 长度：原 17 + 3 个空行
        assertEquals(body.length + K, r.annotated.length)
        assertEquals(body.length + K, r.origToTrans[body.length])
        // 单调不减且严格递增（映射可逆的前提）
        for (i in 0 until body.length) {
            assertTrue("origToTrans[$i]=${r.origToTrans[i]} 必须小于下一项", r.origToTrans[i] < r.origToTrans[i + 1])
        }
        // 块区间：orig [3,14)，trans [3,17)
        val b = r.imageBlocks.single()
        assertEquals(3, b.origStart)
        assertEquals(14, b.origEnd)
        assertEquals(3, b.transStart)
        assertEquals(14 + K, b.transEnd)
        // 正向抽查：块前恒等、行尾换行落在追加空行之后
        assertEquals(2, r.origToTrans[2])
        assertEquals(3, r.origToTrans[3])
        assertEquals(17, r.origToTrans[14])
        assertEquals(18, r.origToTrans[15])
        assertEquals(19, r.origToTrans[16])
    }

    @Test
    fun `反向映射把展开区钳到图片行行尾`() {
        val body = "ab\n[img:t.png]\ncd"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        val m = r.mapping
        // 块前恒等
        assertEquals(2, m.transformedToOriginal(2))
        // 展开区内（含两端）一律钳到原文行尾（光标进不了标记内部）
        for (t in 3..17) assertEquals("t=$t", 14, m.transformedToOriginal(t))
        // 块后 1:1
        assertEquals(15, m.transformedToOriginal(18))
        assertEquals(16, m.transformedToOriginal(19))
        // 正向反向在非图片区一致（原 15='c' ↔ 变换 18）
        assertEquals(18, m.originalToTransformed(15))
        assertEquals(15, m.transformedToOriginal(18))
    }

    @Test
    fun `图片行在文末时映射仍闭合`() {
        val body = "开头\n[img:d.jpg]"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        assertEquals(body.length + K, r.annotated.length)
        assertEquals(body.length + K, r.origToTrans[body.length])
        val b = r.imageBlocks.single()
        assertEquals(3, b.origStart)
        assertEquals(body.length, b.origEnd)
        for (t in b.transStart..b.transEnd) assertEquals(body.length, m2o(r, t))
        // 单调
        for (i in 0 until body.length) assertTrue(r.origToTrans[i] < r.origToTrans[i + 1])
    }

    @Test
    fun `多图片行与后续标签行映射都不串位`() {
        val cb = NoteMarkup.BOX_UNCHECKED_PREFIX
        val body = "[img:a.png]\n中间文字\n[img:b.jpg]\n${cb}任务"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        assertEquals(body.length + 2 * K, r.annotated.length)
        for (i in 0 until body.length) {
            assertTrue(r.origToTrans[i] < r.origToTrans[i + 1])
        }
        assertEquals(body.length + 2 * K, r.origToTrans[body.length])
        assertEquals(2, r.imageBlocks.size)
        // 第二个图片块的原文区间与 "[img:b.jpg]" 的实际位置一致
        val b2 = r.imageBlocks[1]
        assertEquals(body.indexOf("[img:b.jpg]"), b2.origStart)
        assertEquals(body.indexOf("[img:b.jpg]") + "[img:b.jpg]".length, b2.origEnd)
        // 最后的勾选行内容在变换文本中是全角空格占位
        assertTrue(r.annotated.contains("　　任务"))
    }

    private fun m2o(r: NoteTextTransformResult, t: Int): Int = r.mapping.transformedToOriginal(t)

    // ---------- 展示转换 ----------

    @Test
    fun `勾选行占位与无序符号替换`() {
        val r = transformNoteText("☐ 内容\n- 列表", NoteTextSize.DEFAULT.typeScale())
        val text = r.annotated.text
        assertTrue("勾选前缀应渲染为全宽空格", text.startsWith("　　内容"))
        assertTrue("无序前缀应渲染为 •", text.contains("• 列表"))
        assertFalse(text.contains("☐"))
        assertFalse(text.contains("- 列表"))
    }

    // ---------- 退格结构化拦截 ----------

    @Test
    fun `退格拦截_标签行一次删整前缀并落位内容起点`() {
        val old = "第一行\n☐ 买牛奶"   // 内容起点=6,退格删的是前缀空格(idx 5)
        assertEquals("第一行\n买牛奶" to 4, backspaceIntercept(old, 5))
    }

    @Test
    fun `退格拦截_标题加勾选行保留标题标记`() {
        val old = "${NoteMarkup.H1}☐ 内容"
        assertEquals("${NoteMarkup.H1}内容" to 1, backspaceIntercept(old, 2))
    }

    @Test
    fun `退格拦截_图片行删整行含换行`() {
        val old = "ab\n[img:t.png]\ncd"
        assertEquals("ab\ncd" to 2, backspaceIntercept(old, 14))  // 删行尾换行
        assertEquals("ab\ncd" to 2, backspaceIntercept(old, 13))  // 删 ']'
    }

    @Test
    fun `退格拦截_图片行在文末`() {
        val (text, cursor) = backspaceIntercept("开头\n[img:d.jpg]", 13)!!
        assertEquals("开头", text)
        assertEquals(2, cursor)
    }

    @Test
    fun `退格拦截_普通内容不拦截`() {
        assertEquals(null, backspaceIntercept("ab\ncd", 2))      // 正常合并上一行
        assertEquals(null, backspaceIntercept("☐ 内容", 3))      // 行中段删字,默认行为
        assertEquals(null, backspaceIntercept("ab\n普通行", 6))  // 非图片行删换行
    }
}
