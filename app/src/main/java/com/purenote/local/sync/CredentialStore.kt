package com.purenote.local.sync

/**
 * 云同步的凭据存储。
 *
 * **接口与 Android 实现分开的原因**：Keystore 在 JVM 单元测试里不存在，
 * 一旦把 Keystore 调用写死在引擎里，引擎就永远无法在 JVM 上真实测试，
 * 只能靠"编一次能过"来交付。
 *
 * 实现要求（安全约束，不是风格偏好）：
 * - 密码**不得**以明文落盘；
 * - 密码**不得**进入日志、异常消息、崩溃上报；
 * - 取不到密码时返回 null，不要抛出把密钥材料带进堆栈的异常。
 */
interface CredentialStore {

    data class Credentials(val account: String, val appPassword: String) {
        /** 只用于界面回显"已配置账号 X"，绝不回显密码 */
        val maskedAccount: String
            get() = when {
                account.isEmpty() -> ""
                account.length <= 2 -> account.first() + "*"
                else -> account.first() + "*".repeat(account.length - 2) + account.last()
            }
    }

    suspend fun load(): Credentials?

    /** 写入并立即生效；返回 false 表示无法安全保存（此时必须提示用户，不能静默降级为明文）。 */
    suspend fun save(credentials: Credentials): Boolean

    suspend fun clear()
}

/** JVM 测试与降级路径使用的内存实现。绝不用于生产落盘。 */
class InMemoryCredentialStore(
    private var stored: CredentialStore.Credentials? = null,
) : CredentialStore {
    override suspend fun load(): CredentialStore.Credentials? = stored
    override suspend fun save(credentials: CredentialStore.Credentials): Boolean {
        stored = credentials
        return true
    }

    override suspend fun clear() {
        stored = null
    }
}
