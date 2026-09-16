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

}
