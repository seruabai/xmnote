package com.purenote.local.sync

/**
 * 把服务器返回的"技术性"信息翻成用户能照着做的动作。
 *
 * 这里集中处理三类最容易让人卡住的失败（都是真实部署里最常见的）：
 * 1. 用账号登录密码而不是**应用密码**（坚果云必踩）；
 * 2. 仓库地址填成分享链接（带 ? 或 #，或者缺 /dav 结尾）；
 * 3. 父目录不存在（WebDAV 的 PUT 不会自动建目录，必须先 MKCOL）。
 */
object WebDavHints {

    fun forAuth(statusCode: Int, detail: String): String = buildString {
        if (statusCode == 401) {
            append("账号或应用密码不正确。")
            append("若使用坚果云：请在网页端「账户信息 → 安全选项 → 添加应用密码」，用生成的密码登录，不要用登录密码。")
        } else {
            append("服务器拒绝访问该路径（403）。")
            append("请确认应用密码的权限包含读写，且服务器地址指向的是你自己的仓库目录。")
        }
        if (detail.isNotBlank()) append("（服务器说明：").append(detail.take(120)).append("）")
    }

    fun forConflict(detail: String): String = buildString {
        append("服务器说这个位置的父目录不存在或不允许创建。")
        append("请检查服务器地址是否指向已有仓库（WebDAV 的 PUT 不会自动建目录）。")
        if (detail.isNotBlank()) append("（服务器说明：").append(detail.take(120)).append("）")
    }

    fun forPrecondition(detail: String): String = buildString {
        append("远端文件在本次同步期间被改动。")
        if (detail.isNotBlank()) append("（服务器说明：").append(detail.take(120)).append("）")
    }
}
