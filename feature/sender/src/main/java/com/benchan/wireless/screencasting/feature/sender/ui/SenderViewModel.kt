package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benchan.wireless.screencasting.core.common.Hex
import com.benchan.wireless.screencasting.core.data.devices.RecentDevice
import com.benchan.wireless.screencasting.core.data.devices.RecentDevicesStore
import com.benchan.wireless.screencasting.core.data.settings.AppSettings
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.discovery.DeviceDiscovery
import com.benchan.wireless.screencasting.core.discovery.DeviceProtocol
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.net.InetUtils
import com.benchan.wireless.screencasting.core.net.LanInfo
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.protocol.pairing.QrPayload
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import com.benchan.wireless.screencasting.feature.sender.session.CastTarget
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.SenderEvent
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.benchan.wireless.screencasting.feature.control.ControlEvent
import com.benchan.wireless.screencasting.feature.control.ControlUi
import com.benchan.wireless.screencasting.feature.control.SenderControlCoordinator
import javax.inject.Inject

/** 列表中的一行（交互稿 03）。 */
data class DeviceRow(
    val device: DiscoveredDevice,
    val trusted: Boolean,
    val recent: RecentDevice?,
) {
    val enabled: Boolean get() = device.protocol == DeviceProtocol.WSC
}

data class DeviceListUi(
    val recent: List<DeviceRow> = emptyList(),
    val nearby: List<DeviceRow> = emptyList(),
    val searching: Boolean = false,
    val lan: LanInfo? = null,
) {
    val count: Int get() = recent.size + nearby.size
}

@HiltViewModel
class SenderViewModel @Inject constructor(
    private val controller: SenderController,
    private val discovery: DeviceDiscovery,
    private val recentStore: RecentDevicesStore,
    private val trusted: TrustedDeviceStore,
    private val settingsRepo: SettingsRepository,
    private val network: NetworkMonitor,
    private val control: SenderControlCoordinator,
) : ViewModel() {
    /** 反向控制状态（交互稿 R04）。 */
    val controlUi: StateFlow<ControlUi> = control.ui
    val controlEvents: SharedFlow<ControlEvent> = control.events

    val state: StateFlow<SenderState> = controller.state
    val events: SharedFlow<SenderEvent> = controller.events
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lan: StateFlow<LanInfo?> = network.lan

    val devices: StateFlow<DeviceListUi> = combine(
        discovery.devices, recentStore.devices, trusted.devices, discovery.searching, network.lan,
    ) { found, recent, trustedList, searching, lan ->
        val trustedFps = trustedList.map { it.fingerprintHex }.toSet()
        val trustedIds = trustedList.map { it.deviceId }.toSet()
        val recentById = recent.associateBy { it.deviceId }
        val rows = found.map { d ->
            val r = recentById[d.deviceId]
            DeviceRow(d, d.deviceId in trustedIds || (d.fingerprintHex ?: r?.fingerprint) in trustedFps, r)
        }
        // 排序：最近使用（按时间）→ 可信 → 新设备 → 不可用（交互稿 03）
        val (recentRows, others) = rows.partition { it.recent != null && it.enabled }
        DeviceListUi(
            recent = recentRows.sortedByDescending { it.recent?.lastUsedAt ?: 0 },
            nearby = others.sortedWith(compareBy({ !it.enabled }, { !it.trusted }, { it.device.name })),
            searching = searching,
            lan = lan,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceListUi())

    val searchStartedAt: Long get() = discovery.startedAt

    fun startDiscovery() = discovery.start(viewModelScope)
    fun stopDiscovery() = discovery.stop()
    fun restartDiscovery() = discovery.restart(viewModelScope)

    fun connect(row: DeviceRow) {
        val d = row.device
        controller.connect(
            CastTarget(
                deviceId = d.deviceId, name = d.name, host = d.host, port = d.port,
                source = d.sources.firstOrNull { it == DiscoverySource.MDNS } ?: d.sources.first(),
                fingerprintHex = d.fingerprintHex ?: row.recent?.fingerprint,
                fingerprintPrefix = d.fingerprintPrefix,
            ),
        )
    }

    /** Tile 一键投屏：直连最近使用的设备（PRD L14）。 */
    fun quickConnectRecent(): Boolean {
        val r = recentStore.devices.value.firstOrNull() ?: return false
        controller.connect(CastTarget(r.deviceId, r.name, r.host, r.port, DiscoverySource.RECENT, fingerprintHex = r.fingerprint))
        return true
    }

    /** 8 位投屏码（决策 #19）。返回 false 表示本机网段不支持。 */
    fun connectCastCode(code: CastCode): Boolean {
        val l = lan.value ?: return false
        val host = CastCode.resolveHost(l.address.address, l.prefixLength, code.digits.take(3)) ?: return false
        controller.connect(CastTarget(null, host, host, WscProtocol.PREFERRED_PORT, DiscoverySource.CAST_CODE, castCodePin = code.pin))
        return true
    }

    fun connectManual(text: String): Boolean {
        val (host, port) = InetUtils.parseHostPort(text, WscProtocol.PREFERRED_PORT) ?: return false
        controller.connect(CastTarget(null, host, host, port, DiscoverySource.MANUAL))
        return true
    }

    fun connectQr(content: String): Boolean {
        val p = QrPayload.parse(content) ?: return false
        controller.connect(
            CastTarget(
                deviceId = null, name = p.name ?: p.host, host = p.host, port = p.port, source = DiscoverySource.QR,
                fingerprintHex = Hex.encode(p.fingerprint), qrNonce = p.nonce,
            ),
        )
        return true
    }

    /** “重新连接 / 重新投屏”：跳过说明卡，直达系统授权（交互稿 Q11）。 */
    private var skipIntroOnce = false

    fun setSkipIntroOnce() {
        skipIntroOnce = true
    }

    /** peek=true 只查看，不消费。 */
    fun consumeSkipIntroOnce(peek: Boolean): Boolean {
        val v = skipIntroOnce
        if (!peek) skipIntroOnce = false
        return v
    }

    fun reconnectTo(target: CastTarget) = controller.connect(target)

    fun submitPin(pin: String) = controller.submitPin(pin)
    fun cancel() = controller.cancel()
    fun stop() = controller.stop()
    fun acknowledge() = controller.acknowledge()
    fun onCaptureDenied() = controller.onCaptureDenied()
    fun setQuality(q: QualityProfile) = controller.setQuality(q)
    fun undoAutoDowngrade() = controller.undoAutoDowngrade()
    fun setAutoAdjust(enabled: Boolean) = controller.setAutoAdjust(enabled)
    fun setAudioEnabled(enabled: Boolean) = controller.setAudioEnabled(enabled)
    fun revokeControl() = control.revoke()
    fun continueControl() = control.continueDespiteSensitive()

    fun setSkipIntro(skip: Boolean) = viewModelScope.launch { settingsRepo.setSkipCaptureIntro(skip) }
    fun setAudioPreference(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAudioEnabled(enabled) }

    fun forgetDevice(deviceId: String) {
        recentStore.remove(deviceId)
        trusted.remove(deviceId)
    }

    fun renameDevice(deviceId: String, name: String) = recentStore.rename(deviceId, name)

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }
}
