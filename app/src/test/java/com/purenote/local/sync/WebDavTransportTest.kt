package com.purenote.local.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * WebDAV 传输的协议级测试：真跑一次 HTTP（MockWebServer），不是把 OkHttp mock 掉。
 *
 * 覆盖的是最容易"看起来对、实际错"的几处：
 * - 认证头编码（用户名含 @、密码含中文）；
 * - MKCOL 建目录（父目录不存在时 PUT 会 409）；
 * - href 解析（服务器可能返回绝对 URL 或百分号编码）；
 * - 状态码 -> [RemoteException] 子类的映射（认证与限流必须能区分开）。
 */
class WebDavTransportTest {

    private lateinit var server: MockWebServer

    private val config = WebDavConfig(baseUrl = "http://example.invalid/dav/", remoteDir = "purenote")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transport(account: String = "me@example.com", password: String = "app-pass") =
        WebDavTransport(
            config = config.copy(baseUrl = server.url("/dav/").toString()),
            credentials = CredentialStore.Credentials(account, password),
        )

    @Test
    fun `Basic 认证用 UTF-8 编码且不落到 URL 上`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus("/dav/purenote/a%20b.zip")))
        val entries = runBlockingTest { transport(password = "应用密码").list("purenote") }

        val request = server.takeRequest()
        val expected = okhttp3.Credentials.basic("me@example.com", "应用密码", Charsets.UTF_8)
        assertEquals(expected, request.getHeader("Authorization"))
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("PROPFIND", request.method)
        assertEquals("purenote/a b.zip", entries.single().path)
    }

    @Test
    fun `列表解析 207 并过滤自身目录条目`() {
        val absolute = "http://" + server.hostName + ":" + server.port + "/dav/purenote/b%20c.zip"
        val dirEchoNoSlash = server.url("/dav/purenote").toString()
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                // 真实服务器会把被查询的目录自己也列出来：结尾斜杠有的有、有的没有（wsgidav 没有）。
                // 两种都必须被丢掉，否则界面上会多出一个"目录文件"。
                multistatus("/dav/purenote/", collection = true, extra = listOf(dirEchoNoSlash to -1, absolute to 34)),
            ),
        )
        val entries = runBlockingTest { transport().list("purenote") }
        // 自身目录条目被过滤掉（relativeFromHref 对仓库根自身返回 null），只剩那个文件。
        // 断言写成"整列表相等"：一旦多出一条，失败信息会直接告诉我们是哪一条。
        assertEquals(
            listOf(RemoteEntry(path = "purenote/b c.zip", sizeBytes = 34L, etag = "\"x\"")),
            entries.map { it.copy(isDirectory = false) },
        )
    }

    @Test
    fun `上传前自动 MKCOL 建出父目录`() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))
        val payload = ByteArray(4096) { (it % 7).toByte() }
        val entry = runBlockingTest {
            transport().upload("purenote/a.zip", payload.inputStream(), payload.size.toLong())
        }

        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertTrue(mkcol.path!!.endsWith("/dav/purenote/"))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(payload.size.toLong(), put.getHeader("Content-Length")!!.toLong())
        assertEquals("application/zip", put.getHeader("Content-Type"))
        assertEquals(payload.size.toLong(), entry.sizeBytes)
        assertEquals("\"e1\"", entry.etag)
    }

    @Test
    fun `If-Match 条件写 412 翻成冲突而不是覆盖成功`() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(412))
        val payload = "hi".toByteArray()
        val failure = expectFailure {
            transport().upload("purenote/a.zip", payload.inputStream(), payload.size.toLong(), ifMatch = "\"old\"")
        }
        assertTrue("412 应翻成 Conflict", failure is RemoteException.Conflict)
        server.takeRequest()
        assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `状态码映射：401 认证 429 限流 507 超限`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val auth = expectFailure { transport().list("purenote") }
        assertTrue("401 应翻成 Auth，实际 " + auth.javaClass.simpleName, auth is RemoteException.Auth)
        assertTrue(auth.message!!.contains("应用密码"))

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        val limited = expectFailure { transport().list("purenote") }
        assertTrue(limited is RemoteException.RateLimited)
        assertEquals(30_000L, (limited as RemoteException.RateLimited).retryAfterMs)

        server.enqueue(MockResponse().setResponseCode(507))
        val tooLarge = expectFailure {
            transport().upload("purenote/a.zip", "x".toByteArray().inputStream(), 1L)
        }
        assertTrue(tooLarge is RemoteException.TooLarge)
    }

    @Test
    fun `连不上时返回 Unreachable 而不是泄漏 IOException`() {
        val failing = WebDavTransport(
            config = config.copy(baseUrl = "http://127.0.0.1:1/dav/"),
            credentials = CredentialStore.Credentials("a", "b"),
        )
        val failure = expectFailure { failing.stat("purenote/a.zip") }
        assertTrue("实际 " + failure.javaClass.simpleName, failure is RemoteException.Unreachable)
    }

    @Test
    fun `stat 对 404 返回 null 而不是异常`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(runBlockingTest { transport().stat("purenote/missing.zip") })
    }

    // ---- helpers ----

    private fun multistatus(
        href: String,
        collection: Boolean = false,
        /** 额外条目：href 与声明的大小（用 -1 表示这是个目录条目） */
        extra: List<Pair<String, Int>> = emptyList(),
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")
        append(responseBlock(href, if (collection) null else 12, collection))
        extra.forEach { append(responseBlock(it.first, it.second.takeIf { n -> n >= 0 } ?: 0, it.second < 0)) }
        append("</D:multistatus>")
    }

    private fun responseBlock(href: String, length: Int?, collection: Boolean): String = buildString {
        append("<D:response><D:href>").append(href).append("</D:href><D:propstat><D:prop>")
        append(if (collection) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>")
        if (length != null) append("<D:getcontentlength>").append(length).append("</D:getcontentlength>")
        append("<D:getetag>\"x\"</D:getetag>")
        append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    private fun expectFailure(block: suspend () -> Unit): RemoteException = try {
        runBlockingTest { block() }
        fail("期望抛出 RemoteException")
        error("unreachable")
    } catch (e: RemoteException) {
        e
    }

    private fun <T> runBlockingTest(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
