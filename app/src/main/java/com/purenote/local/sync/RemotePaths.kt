package com.purenote.local.sync

import java.net.URI
import java.net.URLEncoder

/**
 * 远端路径规范化。
 *
 * 规则（三条都必须成立，否则"上传到 A、校验读 B"这类错误不会被发现）：
 * 1. 仓库根地址来自用户填写的服务器地址，协议、主机、端口一律保留；
 * 2. 仓库内路径是**相对路径**，统一用 / 分隔，无首尾斜杠，不允许 ..
 * 3. href 解码：WebDAV 的 href 可能是绝对路径、绝对 URL、百分号编码或未编码，
 *    全部要能落回同一种相对路径表示。
 */
object RemotePaths {

    /** 规范化用户填写的仓库根地址。不合法时抛 [IllegalArgumentException]。 */
    fun normalizeBase(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "服务器地址不能为空" }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://" + trimmed
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw IllegalArgumentException("服务器地址格式不正确：" + raw)
        }
        require(uri.scheme == "http" || uri.scheme == "https") { "只支持 http/https 地址" }
        require(!uri.host.isNullOrBlank()) { "服务器地址缺少主机名：" + raw }
        // query / fragment 对 WebDAV 没有意义，带上多半是从浏览器复制的分享链接
        require(uri.query.isNullOrEmpty() && uri.fragment.isNullOrEmpty()) {
            "服务器地址不能带 ? 或 #（请填仓库地址，不要填分享链接）"
        }
        val path = normalizeSegments(uri.rawPath.orEmpty())
        return uri.scheme + "://" + uri.host + (if (uri.port != -1) ":" + uri.port else "") + path
    }

    /** 仓库内相对路径规范化：去首尾斜杠、压缩重复斜杠、拒绝上跳。 */
    fun normalizeRelative(raw: String): String {
        val segs = raw.split('/').filter { it.isNotEmpty() && it != "." }
        require(segs.none { it == ".." }) { "远端路径不允许出现 ..：" + raw }
        return segs.joinToString("/")
    }

    /** 相对路径 -> 绝对 URL 的 path 部分（逐段百分号编码，保留 /）。 */
    fun encodePath(absolutePath: String): String =
        absolutePath.split('/').joinToString("/") { encodeSegment(it) }

    /**
     * href -> 相对仓库路径。
     * @return null 表示这条 href 不属于本次查询（例如列举时服务器把父目录也返回了）
     */
    fun relativeFromHref(base: String, href: String): String? {
        val basePath = runCatching { URI(base).rawPath.orEmpty() }.getOrDefault("")
        val raw = href.trim().substringBefore('#').substringBefore('?')
        if (raw.isEmpty()) return null
        val decoded = runCatching { URI(raw).path.orEmpty() }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val normalizedBase = basePath.trimEnd('/')
        // 列目录时服务器会把**被查询的那个目录自己**也放进 207 里，而且结尾斜杠可有可无
        // （Nextcloud 给 /dav/purenote/，wsgidav 给 /dav/purenote）。两种都要当成"不是文件"丢掉。
        val trimmed = decoded.trimEnd('/')
        val rel = when {
            trimmed == normalizedBase -> return null
            decoded.startsWith(normalizedBase + "/") -> decoded.removePrefix(normalizedBase + "/")
            // 少数服务器返回别的主机名或与仓库根不一致的绝对路径：不认，避免列到别人的目录
            decoded.startsWith("/") -> return null
            else -> decoded
        }
        return normalizeRelativeSafe(rel)
    }

    private fun normalizeRelativeSafe(raw: String): String? {
        val segs = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) return null
        return segs.joinToString("/")
    }

    private fun encodeSegment(seg: String): String =
        URLEncoder.encode(seg, "UTF-8")
            // URLEncoder 是 application/x-www-form-urlencoded 编码，转成 path 段编码
            .replace("+", "%20")

    private fun normalizeSegments(rawPath: String): String {
        val joined = rawPath.split('/').filter { it.isNotEmpty() }.joinToString("/", prefix = "/")
        return if (joined == "/") "" else joined
    }
}
