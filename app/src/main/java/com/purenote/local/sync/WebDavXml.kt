package com.purenote.local.sync

/**
 * 207 Multi-Status 的最小解析器（纯 JVM，无 Android 依赖）。
 *
 * 为什么自己写而不用现成库：SYNC_DESIGN 已定"不引 sardine"（维护弱、带 SimpleXml 依赖）。
 * 这里只需要几个元素（href / getetag / resourcetype / getcontentlength），
 * 用兼容常见写法的小状态机即可，不用 XML 解析器也就没有 XXE 面。
 *
 * 兼容性按真实服务器的常见写法处理：
 * - 命名空间前缀任意（<d:href> / <D:href> / <href>）；
 * - 元素可跨行、含空白与 CDATA；
 * - 自闭合的 resourcetype/collection（部分服务器这样返回目录）。
 */
object WebDavXml {

    data class Resource(
        val href: String,
        val etag: String?,
        val isCollection: Boolean,
        val contentLength: Long?,
    )

    private val responseRegex = Regex(
        "<(?:[A-Za-z0-9_.\\-]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.\\-]+:)?response\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val hrefRegex = Regex(
        "<(?:[A-Za-z0-9_.\\-]+:)?href\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.\\-]+:)?href\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val etagRegex = Regex(
        "<(?:[A-Za-z0-9_.\\-]+:)?getetag\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.\\-]+:)?getetag\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val collectionRegex = Regex(
        "<(?:[A-Za-z0-9_.\\-]+:)?collection\\s*/?\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val lengthRegex = Regex(
        "<(?:[A-Za-z0-9_.\\-]+:)?getcontentlength\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.\\-]+:)?getcontentlength\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val cdataRegex = Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOT_MATCHES_ALL)

    fun parseMultiStatus(xml: String): List<Resource> =
        responseRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val href = unescape(hrefRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null)
            if (href.isBlank()) return@mapNotNull null
            Resource(
                href = href,
                etag = etagRegex.find(block)?.groupValues?.get(1)?.let { unescape(it).trim() }?.ifEmpty { null },
                isCollection = collectionRegex.containsMatchIn(block),
                contentLength = lengthRegex.find(block)?.groupValues?.get(1)
                    ?.let { unescape(it).trim().toLongOrNull() },
            )
        }.toList()

    /** 解析 XML 文本值：去 CDATA、去首尾空白、还原五个预定义实体与数字实体。 */
    internal fun unescape(raw: String): String {
        val withoutCdata = cdataRegex.find(raw)?.groupValues?.get(1) ?: raw
        val sb = StringBuilder(withoutCdata.length)
        var i = 0
        while (i < withoutCdata.length) {
            val c = withoutCdata[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val end = withoutCdata.indexOf(';', i + 1)
            if (end < 0) {
                sb.append(c)
                i++
                continue
            }
            val entity = withoutCdata.substring(i + 1, end)
            val decoded: String? = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (decoded == null) {
                sb.append(c)
                i++
            } else {
                sb.append(decoded)
                i = end + 1
            }
        }
        return sb.toString().trim()
    }
}
