package com.purenote.local.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * WebDAV 传输实现（H 阶段第一实现，SYNC_DESIGN §4.2 定"手写 5 个动词 + 解析 207"）。
 *
 * 只实现这个阶段真正需要的动作：
 *   PROPFIND（列举/取元数据）· MKCOL（建目录）· GET（读回）· PUT（上传）· DELETE
 * 刻意**不实现**：MOVE/COPY/LOCK/分块上传、以及任何双向同步语义。
 *
 * 错误语义全部收敛到 [RemoteException] 的七个子类，上层（同步引擎/界面）只认这些类型，
 * 不去解析 HTTP 状态码或错误字符串。
 */
class WebDavTransport(
    config: WebDavConfig,
    private val credentials: CredentialStore.Credentials,
    private val client: OkHttpClient = defaultClient(),
) : RemoteTransport {

    /** 已规范化的仓库根地址，结尾无斜杠 */
    private val baseUrl: String = RemotePaths.normalizeBase(config.baseUrl)

    private val authorization: String = Credentials.basic(credentials.account, credentials.appPassword, Charsets.UTF_8)

    override val capabilities: StoreCapabilities = StoreCapabilities(
        maxObjectBytes = null,
        listPageLimit = null,
        supportsConditionalPut = true,
        supportsServerHash = false,
    )

    override suspend fun list(prefix: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val rel = RemotePaths.normalizeRelative(prefix)
        val response = execute(propfind(urlFor(rel), "1"))
        val body = response.use { readText(it) }
        WebDavXml.parseMultiStatus(body).mapNotNull { resource ->
            // 被查询的目录自己也会出现在 207 里，而且形式不一：Nextcloud 给 /dav/purenote/，
            // wsgidav 给 /dav/purenote。只靠路径无法百分百识别，所以显式看 resourcetype：
            // 目录不是"文件"，同步只关心文件。
            if (resource.isCollection) return@mapNotNull null
            val path = RemotePaths.relativeFromHref(baseUrl, resource.href) ?: return@mapNotNull null
            if (path.isEmpty()) return@mapNotNull null
            if (rel.isNotEmpty() && path != rel && !path.startsWith(rel + "/")) return@mapNotNull null
            RemoteEntry(
                path = path,
                sizeBytes = resource.contentLength ?: -1L,
                etag = resource.etag,
                isDirectory = resource.isCollection,
            )
        }
    }

    override suspend fun stat(path: String): RemoteEntry? = withContext(Dispatchers.IO) {
        val rel = RemotePaths.normalizeRelative(path)
        val response = execute(propfind(urlFor(rel), "0"), allow = setOf(404))
        response.use { r ->
            if (r.code == 404) return@withContext null
            val entries = WebDavXml.parseMultiStatus(readText(r))
                .mapNotNull { RemotePaths.relativeFromHref(baseUrl, it.href)?.let { p -> p to it } }
            when (val hit = entries.firstOrNull { it.first == rel } ?: entries.firstOrNull()) {
                null -> null
                else -> RemoteEntry(
                    path = hit.first,
                    sizeBytes = hit.second.contentLength ?: -1L,
                    etag = hit.second.etag,
                    isDirectory = hit.second.isCollection,
                )
            }
        }
    }

    override suspend fun openRead(path: String): InputStream = withContext(Dispatchers.IO) {
        val response = execute(
            withAuth(Request.Builder()).url(urlFor(RemotePaths.normalizeRelative(path))).get().build(),
        )
        // 响应体必须在整包读完后才关闭；这里把关闭责任交给返回的 InputStream
        val body = response.body ?: run {
            response.close()
            throw RemoteException.Transport("服务器返回了空响应体")
        }
        object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    override suspend fun upload(
        path: String,
        source: InputStream,
        length: Long,
        ifMatch: String?,
    ): RemoteEntry = withContext(Dispatchers.IO) {
        val rel = RemotePaths.normalizeRelative(path)
        ensureParent(rel)

        val body = object : RequestBody() {
            override fun contentType() = ZIP_MEDIA_TYPE
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                source.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }

        val builder = withAuth(Request.Builder()).url(urlFor(rel)).put(body)
        if (ifMatch != null) builder.header("If-Match", ifMatch)
        val response = execute(builder.build(), allow = setOf(412))
        response.use { r ->
            if (r.code == 412) {
                throw RemoteException.Conflict(
                    412,
                    "远端文件在本次同步期间被改动（可能是另一台设备刚写入）。为避免覆盖较新的备份，本次上传已取消。",
                )
            }
            RemoteEntry(
                path = rel,
                sizeBytes = length,
                etag = r.header("ETag"),
                lastModified = r.header("Last-Modified")?.let { parseHttpDate(it) },
            )
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val rel = RemotePaths.normalizeRelative(path)
            execute(
                withAuth(Request.Builder()).url(urlFor(rel)).delete().build(),
                allow = setOf(404),
            ).close()
        }
    }

    /** 递归建出父目录。已存在（405）与祖先已建好都算成功。 */
    private fun ensureParent(relativePath: String) {
        val segments = relativePath.split('/').dropLast(1).filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var current = ""
        for (seg in segments) {
            current = if (current.isEmpty()) seg else current + "/" + seg
            execute(
                withAuth(Request.Builder()).url(urlFor(current) + "/").method("MKCOL", null).build(),
                allow = setOf(405, 301),
            ).close()
        }
    }

    private fun propfind(url: String, depth: String): Request =
        withAuth(Request.Builder())
            .url(url)
            .header("Depth", depth)
            .header("Accept", "application/xml, text/xml")
            .method("PROPFIND", PROPFIND_BODY)
            .build()

    /**
     * 每个请求都带上 Basic 认证。
     *
     * 没有把它做成 OkHttp 的 `authenticator`：authenticator 只在收到 401 后触发，
     * 那意味着每次同步都要多一轮往返（坚果云免费版有 600 请求/30 分钟的限流）。
     * 直接加在请求头上，只在**确实**被拒时才由调用方看到 Auth 异常。
     */
    private fun withAuth(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", authorization)

    private fun urlFor(relativePath: String): String {
        val path = RemotePaths.encodePath(relativePath)
        return if (path.isEmpty()) "$baseUrl/" else "$baseUrl/$path"
    }

    private fun readText(response: Response): String {
        val body = response.body ?: return ""
        return body.byteStream().use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun execute(request: Request, allow: Set<Int> = emptySet()): Response {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            // 不把账号/密码带进异常消息：OkHttp 的失败消息只含主机与原因
            throw RemoteException.Unreachable(e.message ?: e::class.java.simpleName, e)
        }
        if (response.isSuccessful || response.code in allow) return response

        val detail = runCatching {
            response.body?.byteStream()?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.trim()?.take(300)
        }.getOrNull().orEmpty()
        val code = response.code
        val retryAfter = response.header("Retry-After")?.let { parseRetryAfter(it) }
        response.close()
        throw when (code) {
            401, 403 -> RemoteException.Auth(code, WebDavHints.forAuth(code, detail))
            404 -> RemoteException.NotFound(request.url.encodedPath)
            409 -> RemoteException.Conflict(code, WebDavHints.forConflict(detail))
            412 -> RemoteException.Conflict(code, WebDavHints.forPrecondition(detail))
            423 -> RemoteException.Conflict(code, "文件被服务器锁定，请稍后重试")
            429 -> RemoteException.RateLimited(retryAfter, code)
            507 -> RemoteException.TooLarge(request.url.encodedPath, null)
            in 500..599 -> RemoteException.Server(code, detail.ifBlank { "服务器内部错误" })
            else -> RemoteException.Server(code, detail.ifBlank { "未预期的响应" })
        }
    }

    private fun parseRetryAfter(raw: String): Long? =
        raw.trim().toLongOrNull()?.let { it * 1000 }

    /**
     * 解析 WebDAV 的 Last-Modified（RFC 1123，形如 `Wed, 16 Sep 2026 10:15:00 GMT`）。
     *
     * 用 SimpleDateFormat 而不是 java.time：minSdk 24，java.time 要 26（或开启 desugaring）。
     * 每次 new 一个实例：调用频率极低（只解析响应头），不值得为它引入线程局部缓存。
     */
    private fun parseHttpDate(raw: String): Long? = runCatching {
        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        format.parse(raw)?.time
    }.getOrNull()

    companion object {
        private const val DEFAULT_BUFFER = 64 * 1024
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()

        /**
         * PROPFIND 请求体：只要 href / getetag / getcontentlength / resourcetype。
         * 属性名带 DAV: 前缀是所有 WebDAV 服务器的通用写法（包括坚果云与 Nextcloud）。
         */
        private val PROPFIND_BODY: RequestBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/>
</d:prop></d:propfind>""".toRequestBody("application/xml; charset=utf-8".toMediaType())

        /**
         * 默认客户端。
         *
         * 三个超时都不能省：云盘偶发挂死时，没有超时的请求会一直占着用户的同步按钮。
         * 不注册任何日志拦截器——请求头里有 Authorization，日志里出现一次就等于密码泄露。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()

        /** 把用户填的地址 + 账号密码直接试一次连通性；失败抛 [RemoteException]。 */
        suspend fun probe(config: WebDavConfig, credentials: CredentialStore.Credentials): Int {
            val transport = WebDavTransport(config, credentials)
            val entries = transport.list("")
            return entries.size
        }

    }
}
