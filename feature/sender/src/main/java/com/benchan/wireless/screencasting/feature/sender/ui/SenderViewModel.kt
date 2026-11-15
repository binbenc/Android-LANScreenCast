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
import com.benchan.wireless.screencasting.core.protocol.quality.GameHintType
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
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.feature.control.ControlEvent
import com.benchan.wireless.screencasting.feature.control.ControlUi
import com.benchan.wireless.screencasting.feature.control.SenderControlCoordinator
import javax.inject.Inject

/** 列表中的一行（交互稿 03 / P01）。 */
data class DeviceRow(
    val device: DiscoveredDevice,
    val trusted: Boolean,
    val recent: RecentDevice?,
    /** 上次推送到这台（DLNA，交互稿 P01“最近使用”含推送过的电视）。 */
    val pushedAt: Long? = null,
    /** 正在推送到这台。 */
    val pushing: Boolean = false,
    /** 同一台电视上有多个 DLNA 接收端：副标题加厂商 / 型号区分（T8）。 */
    val disambiguation: String? = null,
) {
    /** WSC 行可镜像；DLNA 行（V1 起）可推送。 */
    val enabled: Boolean get() = device.canMirror || device.canPush
    val dlnaOnly: Boolean get() = device.protocol == DeviceProtocol.DLNA_ONLY
}

/** 推送中的列表顶部横幅（交互稿 P01）。 */
data class PushBanner(val targetName: String, val status: String)

data class DeviceListUi(
    val recent: List<DeviceRow> = emptyList(),
    val nearby: List<DeviceRow> = emptyList(),
    /** “电视自带投屏 · 只能推送”（交互稿 P01）。 */
    val dlna: List<DeviceRow> = emptyList(),
    val searching: Boolean = false,
    val lan: LanInfo? = null,
    val pushing: PushBanner? = null,
) {
    val count: Int get() = recent.size + nearby.size + dlna.size
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
    private val dlnaStore: DlnaRendererStore,
    private val dmc: DmcController,
) : ViewModel() {
    /** 反向控制状态（交互稿 R04）。 */
    val controlUi: StateFlow<ControlUi> = control.ui
    val controlEvents: SharedFlow<ControlEvent> = control.events

    val state: StateFlow<SenderState> = controller.state
    val events: SharedFlow<SenderEvent> = controller.events
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lan: StateFlow<LanInfo?> = network.lan

    val devices: StateFlow<DeviceListUi> = combine(
        combine(discovery.devices, recentStore.devices, trusted.devices, discovery.searching, network.lan) { a, b, c, d, e -> Base(a, b, c, d, e) },
        dlnaStore.records,
        dmc.session,
    ) { base, pushed, push ->
        val trustedFps = base.trusted.map { it.fingerprintHex }.toSet()
        val trustedIds = base.trusted.map { it.deviceId }.toSet()
        val recentById = base.recent.associateBy { it.deviceId }
        val pushedByUdn = pushed.associateBy { it.udn }
        val activePush = push?.takeIf { it.isActive }
        val dlnaHosts = base.found.filter { it.protocol == DeviceProtocol.DLNA_ONLY }.groupBy { it.host }
        val rows = base.found.map { d ->
            val r = recentById[d.deviceId]
            val udn = d.dlna?.udn
            val sameHost = dlnaHosts[d.host].orEmpty().size > 1 && d.protocol == DeviceProtocol.DLNA_ONLY
            DeviceRow(
                d, d.deviceId in trustedIds || (d.fingerprintHex ?: r?.fingerprint) in trustedFps, r,
                pushedAt = udn?.let { pushedByUdn[it]?.lastUsedAt }?.takeIf { it > 0 },
                pushing = udn != null && activePush?.target?.udn == udn,
                disambiguation = if (sameHost) listOfNotNull(d.dlna?.description?.manufacturer, d.dlna?.description?.modelName).joinToString(" ").ifBlank { null } else null,
            )
        }
        // 分组（交互稿 P01）：最近使用（含推送过的 DLNA 电视，按时间）→ 附近设备（本应用接收端）→ 电视自带投屏；每台只出现在一个组
        val (recentRows, others) = rows.partition { (it.recent != null && it.device.canMirror) || (it.dlnaOnly && it.pushedAt != null) }
        val (dlnaRows, wscRows) = others.partition { it.dlnaOnly }
        DeviceListUi(
            recent = recentRows.sortedByDescending { maxOf(it.recent?.lastUsedAt ?: 0, it.pushedAt ?: 0) },
            nearby = wscRows.sortedWith(compareBy({ !it.enabled }, { !it.trusted }, { it.device.name })),
            dlna = dlnaRows.sortedWith(compareBy({ it.device.host }, { it.device.name })),
            searching = base.searching,
            lan = base.lan,
            pushing = activePush?.let { PushBanner(it.target.name, PushCopy.bannerStatus(it)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceListUi())

    private data class Base(
        val found: List<DiscoveredDevice>,
        val recent: List<RecentDevice>,
        val trusted: List<com.benchan.wireless.screencasting.core.security.trust.TrustedDevice>,
        val searching: Boolean,
        val lan: LanInfo?,
    )

    val searchStartedAt: Long get() = discovery.startedAt

    fun startDiscovery() = discovery.start(this)
    fun stopDiscovery() = discovery.stop(this)
    fun restartDiscovery() = discovery.restart(this)

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
        // 优先用发现结果中同 deviceId 的新地址（电视换了 IP 也能连上），没发现时退回旧地址（F24 一并修复）
        val a = com.benchan.wireless.screencasting.feature.sender.elder.QuickConnect.address(r.deviceId, r, discovery.devices.value) ?: return false
        controller.connect(CastTarget(r.deviceId, r.name, a.host, a.port, if (a.fresh) DiscoverySource.MDNS else DiscoverySource.RECENT, fingerprintHex = r.fingerprint))
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

    // F18 游戏模式（交互稿 15 G01 / G04）
    fun answerGameIntro(enable: Boolean) = controller.answerGameIntro(enable)
    fun dismissGameHint(type: GameHintType) = controller.dismissHint(type)
    fun gameHintAction(type: GameHintType) = controller.hintAction(type)
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
        discovery.stop(this)
        super.onCleared()
    }
}
