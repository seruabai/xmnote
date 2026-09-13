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
 * Markdown 正文格式参数化测试：语法解析/标签切换/迁移 + 图片行展开映射 + 退格拦截不变量。
 * JVM 上运行——映射类 bug（曾导致模拟器 ANR）应当在这里先失败。
 */
class NoteMarkupAndTransformTest {

    // ---------- tagInfo（Markdown 语法） ----------

    @Test
    fun `tagInfo 识别标题级别`() {
        assertEquals(1, NoteMarkup.tagInfo("# 标题").level)
        assertEquals(2, NoteMarkup.tagInfo("## 标题").level)
        assertEquals(3, NoteMarkup.tagInfo("### 标题").level)
        assertEquals(4, NoteMarkup.tagInfo("### 标题").headLen)  // "###"+空格=4字符
        assertEquals(0, NoteMarkup.tagInfo("#### 不是标题").level)  // 最多 3 级
        assertEquals(0, NoteMarkup.tagInfo("#没有空格不是标题").level)
        assertEquals(0, NoteMarkup.tagInfo("普通内容").level)
    }

    @Test
    fun `tagInfo 识别各行首标签`() {
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, NoteMarkup.tagInfo("- [ ] 内容").tag)
        assertEquals(6, NoteMarkup.tagInfo("- [ ] 内容").tagLen)
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, NoteMarkup.tagInfo("- [x] 内容").tag)
        assertEquals(NoteMarkup.HeadTag.BULLET, NoteMarkup.tagInfo("- 内容").tag)
        assertEquals(NoteMarkup.HeadTag.NUMBER, NoteMarkup.tagInfo("3. 内容").tag)
        assertEquals(3, NoteMarkup.tagInfo("3. 内容").number)
        assertEquals(NoteMarkup.HeadTag.QUOTE, NoteMarkup.tagInfo("> 内容").tag)
        assertEquals(NoteMarkup.HeadTag.INDENT, NoteMarkup.tagInfo("　　内容").tag)
        assertEquals(NoteMarkup.HeadTag.NONE, NoteMarkup.tagInfo("普通内容").tag)
        // 任务清单优先于无序列表（都以 "- " 开头）
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, NoteMarkup.tagInfo("- [x] 内容").tag)
        // 标题 + 任务叠加
        val info = NoteMarkup.tagInfo("## - [ ] 内容")
        assertEquals(3, info.headLen)
        assertEquals(NoteMarkup.HeadTag.CHECKBOX, info.tag)
    }

    // ---------- toggleHeadTag / 继承 / 缩进 ----------

    @Test
    fun `toggleHeadTag 增删与替换并返回内容起点`() {
        var (line, offset) = NoteMarkup.toggleHeadTag("内容", NoteMarkup.HeadTag.BULLET)
        assertEquals("- 内容", line)
        assertEquals(2, offset)
        val (removed, off2) = NoteMarkup.toggleHeadTag(line, NoteMarkup.HeadTag.BULLET)
        assertEquals("内容", removed)
        assertEquals(0, off2)
        val (num, off3) = NoteMarkup.toggleHeadTag("- 内容", NoteMarkup.HeadTag.NUMBER, numberForNew = 5)
        assertEquals("5. 内容", num)
        assertEquals(3, off3)
        // 标题行加任务:标题保留,偏移含标题长度
        val (h, off4) = NoteMarkup.toggleHeadTag("## 标题", NoteMarkup.HeadTag.CHECKBOX)
        assertEquals("## - [ ] 标题", h)
        assertEquals(9, off4)
    }

    @Test
    fun `inheritTagPrefix 回车继承与序号自增`() {
        assertEquals(NoteMarkup.TASK_TODO, NoteMarkup.inheritTagPrefix("- [ ] 买牛奶"))
        assertEquals("- ", NoteMarkup.inheritTagPrefix("- 已有条目"))
        assertEquals("3. ", NoteMarkup.inheritTagPrefix("2. 上一行"))
        assertEquals("> ", NoteMarkup.inheritTagPrefix("> 引用"))
        assertEquals(NoteMarkup.INDENT_PREFIX, NoteMarkup.inheritTagPrefix("　　缩进行"))
        assertNull(NoteMarkup.inheritTagPrefix("普通行"))
        assertNull(NoteMarkup.inheritTagPrefix("# 标题行"))
    }

    @Test
    fun `toggleTailIndent 行尾加减缩进`() {
        assertEquals("内容　　", NoteMarkup.toggleTailIndent("内容"))
        assertEquals("内容", NoteMarkup.toggleTailIndent("内容　　"))
    }

    @Test
    fun `cycleCheckboxLine 任务语法勾选切换`() {
        assertEquals("- [x] 买牛奶", NoteMarkup.cycleCheckboxLine("- [ ] 买牛奶"))
        assertEquals("- [ ] 买牛奶", NoteMarkup.cycleCheckboxLine("- [x] 买牛奶"))
        assertEquals("普通", NoteMarkup.cycleCheckboxLine("普通"))
    }

    // ---------- 旧格式迁移 ----------

    @Test
    fun `迁移_PUA标题转Markdown`() {
        assertEquals("# 标题", NoteMarkup.migrateBodyV1toV2("\uE201标题"))
        assertEquals("## 标题", NoteMarkup.migrateBodyV1toV2("\uE202标题"))
        assertEquals("### 标题", NoteMarkup.migrateBodyV1toV2("\uE203标题"))
    }

    @Test
    fun `迁移_勾选前缀转任务语法`() {
        assertEquals("- [ ] 买牛奶", NoteMarkup.migrateBodyV1toV2("☐ 买牛奶"))
        assertEquals("- [x] 已完成", NoteMarkup.migrateBodyV1toV2("☑ 已完成"))
        assertEquals("## - [ ] 标题下的任务", NoteMarkup.migrateBodyV1toV2("\uE202☐ 标题下的任务"))
    }

    @Test
    fun `迁移_图片行转Markdown图片`() {
        assertEquals("![](t.png)", NoteMarkup.migrateBodyV1toV2("[img:t.png]"))
        assertEquals("前文\n![](aud_1.m4a)", NoteMarkup.migrateBodyV1toV2("前文\n[img:aud_1.m4a]"))
    }

    @Test
    fun `迁移_幂等且不破坏已有Markdown`() {
        val md = "# 标题\n- [ ] 任务\n- 无序\n1. 有序\n> 引用\n　　缩进\n![](t.png)\n普通行"
        assertEquals(md, NoteMarkup.migrateBodyV1toV2(md))
        assertEquals("原样", NoteMarkup.migrateBodyV1toV2("原样"))
    }

    // ---------- transformNoteText 映射不变量 ----------

    private val K = 3 // IMAGE_BLOCK_EXTRA_LINES

    @Test
    fun `无图片行时映射为恒等`() {
        val body = "第一行\n- [ ] 买牛奶\n普通行"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        for (i in 0..body.length) assertEquals(i, r.origToTrans[i])
        assertEquals(body.length, r.annotated.length)
        assertTrue(r.imageBlocks.isEmpty())
    }

    @Test
    fun `图片行展开后映射单调且端点正确`() {
        val body = "ab\n![](t.png)\ncd"   // 图片行 10 字符,原 16
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        assertEquals(body.length + K, r.annotated.length)
        assertEquals(body.length + K, r.origToTrans[body.length])
        for (i in 0 until body.length) {
            assertTrue("origToTrans[$i]=${r.origToTrans[i]} 必须严格递增", r.origToTrans[i] < r.origToTrans[i + 1])
        }
        val b = r.imageBlocks.single()
        assertEquals(3, b.origStart)
        assertEquals(13, b.origEnd)
        assertEquals(3, b.transStart)
        assertEquals(13 + K, b.transEnd)
        assertEquals(2, r.origToTrans[2])
        assertEquals(3, r.origToTrans[3])
        assertEquals(16, r.origToTrans[13])
        assertEquals(17, r.origToTrans[14])
    }

    @Test
    fun `反向映射把展开区钳到图片行行尾`() {
        val body = "ab\n![](t.png)\ncd"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        val m = r.mapping
        assertEquals(2, m.transformedToOriginal(2))
        for (t in 3..16) assertEquals("t=$t", 13, m.transformedToOriginal(t))
        assertEquals(14, m.transformedToOriginal(17))
        assertEquals(15, m.transformedToOriginal(18))
        assertEquals(17, m.originalToTransformed(14))
    }

    @Test
    fun `图片行在文末时映射仍闭合`() {
        val body = "开头\n![](d.jpg)"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        assertEquals(body.length + K, r.annotated.length)
        assertEquals(body.length + K, r.origToTrans[body.length])
        val b = r.imageBlocks.single()
        assertEquals(3, b.origStart)
        assertEquals(body.length, b.origEnd)
        for (t in b.transStart..b.transEnd) assertEquals(body.length, r.mapping.transformedToOriginal(t))
        for (i in 0 until body.length) assertTrue(r.origToTrans[i] < r.origToTrans[i + 1])
    }

    @Test
    fun `多图片行与后续标签行映射都不串位`() {
        val body = "![](a.png)\n中间文字\n![](b.jpg)\n- [ ] 任务"
        val r = transformNoteText(body, NoteTextSize.DEFAULT.typeScale())
        assertEquals(body.length + 2 * K, r.annotated.length)
        for (i in 0 until body.length) {
            assertTrue(r.origToTrans[i] < r.origToTrans[i + 1])
        }
        val b2 = r.imageBlocks[1]
        assertEquals(body.indexOf("![](b.jpg)"), b2.origStart)
        assertEquals(body.indexOf("![](b.jpg)") + "![](b.jpg)".length, b2.origEnd)
    }

    // ---------- 展示转换 ----------

    @Test
    fun `任务占位与无序符号替换`() {
        val r = transformNoteText("- [ ] 内容\n- 列表", NoteTextSize.DEFAULT.typeScale())
        val text = r.annotated.text
        assertTrue("任务前缀渲染为等长占位(2全角+4半角)", text.startsWith("　　\u2002\u2002\u2002\u2002内容"))
        assertTrue("无序前缀渲染为 •", text.contains("• 列表"))
        assertFalse(text.contains("- [ ]"))
        assertFalse(text.contains("☐"))
    }

    @Test
    fun `标题标记零宽化且字号分级`() {
        val r = transformNoteText("# 一级\n### 三级\n正文", NoteTextSize.DEFAULT.typeScale())
        val text = r.annotated.text
        assertFalse(text.contains("#"))
        assertTrue(text.contains("一级"))
        assertTrue(text.contains("正文"))
    }

    // ---------- 退格结构化拦截 ----------

    @Test
    fun `退格拦截_标签行一次删整前缀并落位内容起点`() {
        val old = "第一行\n- [ ] 买牛奶"   // 内容起点=10,退格删的是前缀末字符(idx 9)
        assertEquals("第一行\n买牛奶" to 4, backspaceIntercept(old, 9))
    }

    @Test
    fun `退格拦截_标题加任务行保留标题标记`() {
        val old = "## - [ ] 内容"
        assertEquals("## 内容" to 3, backspaceIntercept(old, 8))
    }

    @Test
    fun `退格拦截_图片行删整行含换行`() {
        val old = "ab\n![](t.png)\ncd"
        assertEquals("ab\ncd" to 2, backspaceIntercept(old, 13))  // 删行尾换行
        assertEquals("ab\ncd" to 2, backspaceIntercept(old, 12))  // 删 ')'
    }

    @Test
    fun `退格拦截_图片行在文末`() {
        val (text, cursor) = backspaceIntercept("开头\n![](d.jpg)", 12)!!
        assertEquals("开头", text)
        assertEquals(2, cursor)
    }

    @Test
    fun `退格拦截_普通内容不拦截`() {
        assertEquals(null, backspaceIntercept("ab\ncd", 2))      // 正常合并上一行
        assertEquals(null, backspaceIntercept("- [ ] 内容", 6))  // 行内容首字符,默认行为
        assertEquals(null, backspaceIntercept("ab\n普通行", 6))  // 非图片行删换行
    }

    // ---------- 回车继承拦截 ----------

    @Test
    fun `回车继承_光标在行中时保留光标后的文本`() {
        // 任务行内容起点按回车:新行 = 剩余全部文本,修复前会被整行替换清空
        val newText = "- [ ] \nABCDEF"
        assertEquals("- [ ] \n- [ ] ABCDEF" to 13, enterInheritIntercept(newText, 7))
    }

    @Test
    fun `回车继承_行后还有内容时尾部必须保留`() {
        // 设备上真实场景:继承行后面还有整个文档尾部(此前实现把尾部丢了)
        val newText = "- [ ] ABC\nDEF\nGHI"   // 光标在 DEF 行首(刚插入的换行之后)
        assertEquals("- [ ] ABC\n- [ ] DEF\nGHI" to 16, enterInheritIntercept(newText, 10))
    }

    @Test
    fun `回车继承_光标在行尾正常续前缀`() {
        assertEquals("- [ ] ABC\n- [ ] " to 16, enterInheritIntercept("- [ ] ABC\n", 10))
        assertEquals("2. x\n3. " to 8, enterInheritIntercept("2. x\n", 5))
    }

    @Test
    fun `回车继承_无标签不拦截`() {
        assertEquals(null, enterInheritIntercept("ab\ncd", 3))
    }
}
