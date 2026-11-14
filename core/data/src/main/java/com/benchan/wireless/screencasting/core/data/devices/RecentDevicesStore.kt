package com.benchan.wireless.screencasting.core.data.devices

import android.content.Context
import com.benchan.wireless.screencasting.core.data.JsonFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发送端“最近连接的设备”（PRD 8.4）：缓存上次的 IP、端口和指纹，组播被屏蔽但 IP 没变时直接用 IP 连。
 */
@Serializable
data class RecentDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    /** 接收端完整证书指纹（hex）。 */
    val fingerprint: String,
    val lastUsedAt: Long,
    val useCount: Int = 1,
)

@Singleton
class RecentDevicesStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFileStore(
        File(context.noBackupFilesDir, "recent_devices.json"),
        ListSerializer(RecentDevice.serializer()),
    ) { emptyList() }

    private val _devices = MutableStateFlow(store.read().sortedByDescending { it.lastUsedAt })
    val devices: StateFlow<List<RecentDevice>> = _devices.asStateFlow()

    fun record(device: RecentDevice) {
        val next = store.update { list ->
            val old = list.firstOrNull { it.deviceId == device.deviceId }
            (listOf(device.copy(useCount = (old?.useCount ?: 0) + 1)) + list.filterNot { it.deviceId == device.deviceId })
                .sortedByDescending { it.lastUsedAt }
                .take(MAX)
        }
        _devices.value = next
    }

    fun rename(deviceId: String, name: String) {
        _devices.value = store.update { list -> list.map { if (it.deviceId == deviceId) it.copy(name = name) else it } }
    }

    fun remove(deviceId: String) {
        _devices.value = store.update { list -> list.filterNot { it.deviceId == deviceId } }
    }

    fun find(deviceId: String): RecentDevice? = _devices.value.firstOrNull { it.deviceId == deviceId }

    private companion object {
        const val MAX = 10
    }
}
