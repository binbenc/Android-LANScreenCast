package com.benchan.wireless.screencasting.core.security.trust

import com.benchan.wireless.screencasting.core.data.JsonFileStore
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** 可信设备（配对成功后写入完整指纹，决策 #13）。 */
@Serializable
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val fingerprintHex: String,
    val addedAt: Long,
    val lastSeenAt: Long,
) {
    val fingerprint: Fingerprint get() = Fingerprint.fromHex(fingerprintHex)
}

/** 可信设备列表：设置里可以逐个移除（PRD 8.4 第 4 步）。 */
class TrustedDeviceStore(file: File) {
    private val store = JsonFileStore(file, ListSerializer(TrustedDevice.serializer())) { emptyList() }
    private val _devices = MutableStateFlow(store.read())
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    /** 以指纹为准判断是否可信（deviceId 可伪造，指纹由 TLS 握手证明）。 */
    fun isTrusted(fingerprint: Fingerprint): Boolean = _devices.value.any { it.fingerprintHex == fingerprint.hex }

    fun findByFingerprint(fingerprint: Fingerprint): TrustedDevice? = _devices.value.firstOrNull { it.fingerprintHex == fingerprint.hex }

    fun findById(deviceId: String): TrustedDevice? = _devices.value.firstOrNull { it.deviceId == deviceId }

    fun add(deviceId: String, name: String, fingerprint: Fingerprint, now: Long = System.currentTimeMillis()) {
        _devices.value = store.update { list ->
            listOf(TrustedDevice(deviceId, name, fingerprint.hex, list.firstOrNull { it.deviceId == deviceId }?.addedAt ?: now, now)) +
                list.filterNot { it.deviceId == deviceId || it.fingerprintHex == fingerprint.hex }
        }
    }

    fun touch(fingerprint: Fingerprint, name: String? = null, now: Long = System.currentTimeMillis()) {
        _devices.value = store.update { list ->
            list.map { if (it.fingerprintHex == fingerprint.hex) it.copy(lastSeenAt = now, name = name ?: it.name) else it }
        }
    }

    fun remove(deviceId: String) {
        _devices.value = store.update { list -> list.filterNot { it.deviceId == deviceId } }
    }
}
