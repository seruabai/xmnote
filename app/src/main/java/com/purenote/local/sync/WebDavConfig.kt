package com.purenote.local.sync

/**
 * 连接参数（不含密钥）。密钥一律走 [CredentialStore]，不进这个对象、不进日志。
 */
data class WebDavConfig(
    /** 已规范化的仓库根地址，例如 https://dav.jianguoyun.com/dav/ */
    val baseUrl: String,
    /** 远端目录（相对仓库根）。空串表示直接用仓库根目录。 */
    val remoteDir: String = DEFAULT_DIR,
    /** 上次成功同步时间；0 表示从未成功 */
    val lastSuccessAt: Long = 0L,
    /** 上次成功同步的是哪个备份包（backupId），用于识别"远端被别的设备换过" */
    val lastBackupId: String = "",
    /** 上次成功同步的包大小，用于界面显示 */
    val lastBytes: Long = 0L,
) {
    companion object {
        /** 默认放在子目录里，避免直接往用户网盘根目录扔文件 */
        const val DEFAULT_DIR = "purenote"
    }
}
