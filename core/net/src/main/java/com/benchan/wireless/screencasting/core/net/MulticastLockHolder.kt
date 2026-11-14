package com.benchan.wireless.screencasting.core.net

import android.content.Context
import android.net.wifi.WifiManager
import com.benchan.wireless.screencasting.core.common.WscLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 组播锁（PRD 4.1①、技术方案 3.6）：部分设备不持有 MulticastLock 就收不到 mDNS / SSDP 响应。
 * 只在发现期间（发现页、接收端待机前台）持有，离开即释放。按持有者引用计数。
 */
@Singleton
class MulticastLockHolder @Inject constructor(@ApplicationContext context: Context) {
    private val lock: WifiManager.MulticastLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("wsc-discovery").apply { setReferenceCounted(false) }
    }.getOrNull()
    private val holders = mutableSetOf<String>()

    @Synchronized
    fun acquire(owner: String) {
        holders += owner
        val l = lock ?: return
        if (!l.isHeld) {
            runCatching { l.acquire() }.onFailure { WscLog.w(TAG, "acquire failed", it) }
        }
    }

    @Synchronized
    fun release(owner: String) {
        holders -= owner
        val l = lock ?: return
        if (holders.isEmpty() && l.isHeld) runCatching { l.release() }
    }

    val isHeld: Boolean get() = lock?.isHeld == true

    private companion object {
        const val TAG = "MulticastLock"
    }
}
