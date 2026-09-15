package com.purenote.local.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 路径与 207 解析的纯函数测试。
 *
 * 这两块是整个 H 阶段最容易"看起来对、实际错"的地方：
 * 路径算错时上传与校验会指向不同的文件，而两边都返回成功。
 */
class WebDavPureTest {

    // ---- RemotePaths.normalizeBase ----

    @Test
    fun `base 保留端口并按用户习惯补全协议`() {
        assertEquals("https://dav.jianguoyun.com/dav", RemotePaths.normalizeBase("dav.jianguoyun.com/dav"))
        assertEquals("https://dav.jianguoyun.com/dav", RemotePaths.normalizeBase("https://dav.jianguoyun.com/dav/"))
        assertEquals("http://192.168.1.5:8080/remote.php/dav/files/me",
            RemotePaths.normalizeBase("http://192.168.1.5:8080/remote.php/dav/files/me/"))
        assertEquals("https://example.com", RemotePaths.normalizeBase("https://example.com"))
        assertEquals("https://example.com", RemotePaths.normalizeBase("  https://example.com/  "))
    }

    @Test
    fun `base 拒绝分享链接与非法协议`() {
        assertTrue(assertThrows { RemotePaths.normalizeBase("https://pan.example.com/s/abc?share=1") }
            .contains("分享链接"))
        assertTrue(assertThrows { RemotePaths.normalizeBase("ftp://example.com") }.contains("http"))
        assertTrue(assertThrows { RemotePaths.normalizeBase("") }.contains("不能为空"))
    }

    @Test
    fun `相对路径去斜杠并拒绝上跳`() {
        assertEquals("purenote", RemotePaths.normalizeRelative("/purenote/"))
        assertEquals("a/b", RemotePaths.normalizeRelative("a//b/"))
        assertEquals("", RemotePaths.normalizeRelative("/"))
        assertTrue(assertThrows { RemotePaths.normalizeRelative("../etc") }.contains(".."))
    }

    @Test
    fun `href 支持绝对路径 绝对URL 与百分号编码`() {
        val base = "https://dav.jianguoyun.com/dav"
        assertEquals("purenote/a.zip", RemotePaths.relativeFromHref(base, "/dav/purenote/a.zip"))
        assertEquals("purenote/a.zip", RemotePaths.relativeFromHref(base, "https://dav.jianguoyun.com/dav/purenote/a.zip"))
        assertEquals("purenote/中文 名.zip", RemotePaths.relativeFromHref(base, "/dav/purenote/%E4%B8%AD%E6%96%87%20%E5%90%8D.zip"))
        assertEquals("purenote/a.zip", RemotePaths.relativeFromHref("$base/", "/dav/purenote/a.zip"))
    }

    @Test
    fun `href 不属于仓库或指向上层的返回 null`() {
        val base = "https://dav.jianguoyun.com/dav"
        assertNull(RemotePaths.relativeFromHref(base, "/dav/"))
        assertNull(RemotePaths.relativeFromHref(base, "/other/purenote/a.zip"))
        assertNull(RemotePaths.relativeFromHref(base, "/dav/../secret/a.zip"))
        assertNull(RemotePaths.relativeFromHref(base, ""))
    }

    @Test
    fun `encodePath 只逐段编码`() {
        assertEquals("/dav/purenote/a%20b.zip", RemotePaths.encodePath("/dav/purenote/a b.zip"))
        assertEquals("/dav/%E4%B8%AD%E6%96%87.zip", RemotePaths.encodePath("/dav/中文.zip"))
    }

    // ---- WebDavXml.parseMultiStatus ----

    @Test
    fun `解析 207 列表 兼容前缀与跨行`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/purenote/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/purenote/a.zip</D:href>
                <D:propstat><D:prop>
                  <D:resourcetype/>
                  <D:getcontentlength>1048576</D:getcontentlength>
                  <D:getetag>"abc-123"</D:getetag>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val parsed = WebDavXml.parseMultiStatus(xml)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isCollection)
        assertTrue(!parsed[1].isCollection)
        assertEquals(1048576L, parsed[1].contentLength)
        assertEquals("\"abc-123\"", parsed[1].etag)
    }

    @Test
    fun `无前缀命名空间 与 CDATA 也要能解析`() {
        val xml = """
            <multistatus xmlns="DAV:">
              <response>
                <href><![CDATA[/dav/purenote/b.zip]]></href>
                <propstat><prop><getetag>W/"weak"</getetag></prop></propstat>
              </response>
            </multistatus>
        """.trimIndent()
        val parsed = WebDavXml.parseMultiStatus(xml)
        assertEquals(1, parsed.size)
        assertEquals("/dav/purenote/b.zip", parsed[0].href)
        assertEquals("W/\"weak\"", parsed[0].etag)
    }

    @Test
    fun `空响应体不抛异常只返回空列表`() {
        assertTrue(WebDavXml.parseMultiStatus("").isEmpty())
        assertTrue(WebDavXml.parseMultiStatus("<html>404</html>").isEmpty())
    }

    // ---- SyncContract ----

    @Test
    fun `云端文件名与路径稳定可读`() {
        val t = 1_800_000_000_000L
        val name = SyncContract.fileName(t)
        assertTrue(name.startsWith("purenote-"))
        assertTrue(name.endsWith(".purenote.zip"))
        assertEquals("purenote/" + name, SyncContract.remotePath("purenote", name))
        assertEquals(name, SyncContract.remotePath("", name))
        assertTrue(SyncContract.isBackupFile(name))
        assertTrue(!SyncContract.isBackupFile("purenote/readme.txt"))
    }

    private fun assertThrows(block: () -> Unit): String {
        val e = try {
            block()
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertTrue("期望抛出 IllegalArgumentException", e != null)
        return e!!.message.orEmpty()
    }
}
