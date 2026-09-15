package com.purenote.local.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络可用性判定（H 阶段要求：在"云同步"开关处才申请权限，并给出可读的不可用说明）。
 *
 * 判定用 ConnectivityManager 而不是"试着连一次"：用户没网时应该立刻得到
 * "当前无网络"这句人话，而不是等 20 秒超时再报"无法连接服务器"。
 *
 * 只用 NET_CAPABILITY_INTERNET（"这个网络声称能上外网"），**要求 VALIDATED**。
 * 实测教训：Android 的网络探测走的是 Google 的 generate_204，在国内网络下经常判定不通过，
 * 于是"系统说有网、实际能连坚果云"的场景会被我们自己的门禁挡死。
 * 真正的失败仍以传输异常为准：这里只是提前给一句可读的话，不是权威判定。
 */
interface NetworkStatus {
    fun isOnline(): Boolean
    /** 当前是否按流量计费（移动网络/热点）。首版只用于提示，不做"仅 WiFi"限制。 */
    fun isMetered(): Boolean
}

class AndroidNetworkStatus(context: Context) : NetworkStatus {

    private val appContext = context.applicationContext

    override fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isMetered(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }
}

/** 测试用：显式指定"有网/无网"。 */
class FixedNetworkStatus(
    private val online: Boolean,
    private val metered: Boolean = false,
) : NetworkStatus {
    override fun isOnline(): Boolean = online
    override fun isMetered(): Boolean = metered
}
