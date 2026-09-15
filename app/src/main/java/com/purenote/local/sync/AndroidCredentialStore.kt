package com.purenote.local.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 保管 WebDAV 应用密码（SYNC_DESIGN §4.3：security-crypto 已废弃，
 * 直接用 Keystore + AES-GCM）。
 *
 * 落盘形态：密文与 IV 用 Base64 存进 SharedPreferences；密钥本身**只存在于 Keystore**，
 * 不可导出。没有 Keystore 时（极老设备/异常环境）保存返回 false，
 * 由界面如实告诉用户"无法安全保存"，**不降级为明文**。
 *
 * 威胁模型（如实声明，不夸大）：能 root/取证到 Keystore 解密能力的攻击者仍可读到密码；
 * 这条防线主要挡住"备份文件被拷走"和"应用目录被读"这两类常见泄露。
 */
class AndroidCredentialStore(context: Context) : CredentialStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): CredentialStore.Credentials? = withContext(Dispatchers.IO) {
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return@withContext null
        val blob = prefs.getString(KEY_BLOB, null) ?: return@withContext null
        val iv = prefs.getString(KEY_IV, null) ?: return@withContext null
        val password = runCatching {
            val plain = decrypt(Base64.decode(blob, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8)
        }.getOrNull() ?: return@withContext null
        CredentialStore.Credentials(account, password)
    }

    override suspend fun save(credentials: CredentialStore.Credentials): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val encrypted = encrypt(credentials.appPassword.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(KEY_ACCOUNT, credentials.account)
                .putString(KEY_BLOB, Base64.encodeToString(encrypted.first, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(encrypted.second, Base64.NO_WRAP))
                .commit()
        }.getOrElse { false }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_ACCOUNT).remove(KEY_BLOB).remove(KEY_IV).commit()
        }
    }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.doFinal(plain) to cipher.iv
    }

    private fun decrypt(blob: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(blob)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 云同步必须能在后台完成，因此不要求设备解锁/生物认证
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "purenote.webdav.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFS_NAME = "purenote_cloud_credentials"
        const val KEY_ACCOUNT = "account"
        const val KEY_BLOB = "blob"
        const val KEY_IV = "iv"
    }
}
