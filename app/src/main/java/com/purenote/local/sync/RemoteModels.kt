package com.purenote.local.sync

import java.io.InputStream

/**
 * 远端仓库的抽象（H 阶段 · 规范 §18 / SYNC_DESIGN §4.2）。
 *
 * 设计原则（照抄既有决策，不重新发明）：
 * 1. **抽象只描述文件动作**：列举、下载、上传、删除。同步智能一律写在它之上，只写一遍。
 * 2. **接口里不允许出现 provider 标志位或厂商字段**。厂商差异只允许存在于实现内部、
 *    [StoreCapabilities] 与异常类型。
 * 3. 传输单位是**完整备份包**（阶段 E 的 zip，带 manifest 与逐项 SHA-256），
 *    **不是**逐条记录。首版不做自动双向同步（规范 §17 阶段 H 明确要求"不顺带实现未经设计的双向同步"）。
 */

/** 远端上的一个文件（或目录）。 */
data class RemoteEntry(
    /** 相对仓库根目录的路径，例如 backups/purenote-20260916-1015.purenote.zip */
    val path: String,
    val sizeBytes: Long = -1L,
    val lastModified: Long? = null,
    val etag: String? = null,
    val isDirectory: Boolean = false,
)

/** 能力声明。调用方只能读这里来适配厂商差异，不能去嗅探实现类。 */
data class StoreCapabilities(
    /** 单文件上限；null 表示未知（未知时不做预判，让服务器自己拒绝） */
    val maxObjectBytes: Long? = null,
    /** 一次列举最多返回多少项；null 表示不再分页。坚果云免费版为 750 */
    val listPageLimit: Int? = null,
    /** 是否支持 If-Match 条件写（用于防止覆盖别人刚写上去的包） */
    val supportsConditionalPut: Boolean = false,
    /** 是否提供服务器侧内容哈希（多数 WebDAV 不提供，只给 etag；etag 不等于内容哈希） */
    val supportsServerHash: Boolean = false,
)

/**
 * 远端失败的一等公民。
 *
 * 为什么必须分类而不是抛一个 IOException：用户看到的提示取决于是"密码错"还是"没网"还是
 * "被限流"，而且**限流必须能被上层识别出来退避**，不能靠解析错误字符串。
 */
sealed class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 认证/授权失败（401/403）。坚果云用账号密码会走到这里，必须用应用密码。 */
    class Auth(val statusCode: Int, detail: String) :
        RemoteException(if (statusCode == 401) "认证失败：账号或应用密码不正确" else "没有权限：$detail")

    /** 地址/证书/连通性层面的错误：连不上、DNS、TLS、超时。 */
    class Unreachable(detail: String, cause: Throwable? = null) :
        RemoteException("无法连接服务器：$detail", cause)

    /** 被服务端限流。坚果云免费版为 600 请求/30 分钟。 */
    class RateLimited(val retryAfterMs: Long?, val statusCode: Int) :
        RemoteException(
            "服务器限流（HTTP $statusCode）" +
                (retryAfterMs?.let { "，请约 " + (it / 1000) + " 秒后重试" } ?: ""),
        )

    class NotFound(val remotePath: String) : RemoteException("远端不存在：$remotePath")

    /** 412/409/423：条件写失败、父目录不存在、资源被锁 */
    class Conflict(val statusCode: Int, detail: String) :
        RemoteException("远端冲突（HTTP $statusCode）：$detail")

    class TooLarge(val remotePath: String, val limitBytes: Long?) :
        RemoteException(
            "文件超出服务器上限：" + (limitBytes?.let { (it / 1024 / 1024).toString() + " MB" } ?: "未知上限"),
        )

    /** 5xx 与其它未归类的失败 */
    class Server(val statusCode: Int, detail: String) :
        RemoteException("服务器错误（HTTP $statusCode）：$detail")

    /** 网络中断、超时等在传输途中发生、可整体重试的错误 */
    class Transport(detail: String, cause: Throwable? = null) :
        RemoteException("传输中断：$detail", cause)
}

/**
 * 只有文件动作的传输接口。
 *
 * 刻意做成"够用就好"的几个动作：列举、取元数据、流式读、流式写、删除。
 * 备份包可能有几十 MB，**不允许**用 ByteArray 作为接口形状，
 * 否则实现者会被迫把整包读进内存。
 */
interface RemoteTransport {

    val capabilities: StoreCapabilities

    /** 列举 [prefix] 下的直属文件。 */
    suspend fun list(prefix: String): List<RemoteEntry>

    /** 打开远端文件用于读取。调用方负责关闭。 */
    suspend fun openRead(path: String): InputStream

    /** 取单个文件的元数据；不存在返回 null（不是异常）。 */
    suspend fun stat(path: String): RemoteEntry?

    /**
     * 流式上传（覆盖写）。
     * @param length 必须精确；实现必须把它写进 Content-Length，不能靠 chunked 猜
     * @param ifMatch 非 null 时作为 If-Match 发送，实现里不得静默降级为无条件覆盖
     */
    suspend fun upload(path: String, source: InputStream, length: Long, ifMatch: String? = null): RemoteEntry

    /** 删除；不存在视为成功（幂等）。 */
    suspend fun delete(path: String)
}
