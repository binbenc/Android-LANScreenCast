package com.benchan.wireless.screencasting.feature.sender.elder

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.devices.RecentDevicesStore
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.core.data.settings.AppSettings
import com.benchan.wireless.screencasting.core.data.settings.ElderTarget
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.discovery.DeviceDiscovery
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.net.LanInfo
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import com.benchan.wireless.screencasting.feature.sender.session.CastTarget
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** 首页上一台电视的此刻状态（交互稿 E01 状态行）。 */
data class ElderTargetUi(
    val target: ElderHomeTarget,
    /** null = 正在找；true = 已找到；false = 没找到（10 秒）。 */
    val found: Boolean?,
    val canMirror: Boolean,
    val canPush: Boolean,
)

/** 正在找 / 连的那台（E06“正在连接 X…”）或失败卡（E08）。 */
data class ElderAttempt(val label: String, val finding: Boolean = true, val outcome: ElderOutcome? = null, val photos: Boolean = false)

/** E04 选其他设备的一行。 */
data class ElderOtherDevice(val device: DiscoveredDevice, val canMirror: Boolean, val canPush: Boolean)

/** 向导 E03 的一个候选（只列不需要再配对就能用的设备；同一台本应用接收端的镜像与推送身份合并为一条）。 */
data class ElderCandidate(val target: ElderTarget, val trusted: Boolean, val canMirror: Boolean, val canPush: Boolean, val lastUsedAt: Long = 0)

/**
 * 长辈模式（F24，PRD 8.1.4）的 ViewModel：首页目标与找电视状态、一键投屏（最多找 8 秒，优先用发现结果里的新地址）、
 * 放照片的目标、失败原因、“请家人帮忙”文本、向导与退出。镜像 / 推送本身完全复用 [SenderController] / [DmcController]。
 * 不自动发起镜像（决策 #44）：只有用户点按钮（或快捷设置图块）才调用 [cast]。
 */
@HiltViewModel
class ElderViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val discovery: DeviceDiscovery,
    private val recent: RecentDevicesStore,
    private val trusted: TrustedDeviceStore,
    private val dlna: DlnaRendererStore,
    private val controller: SenderController,
    private val dmc: DmcController,
    network: NetworkMonitor,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val state: StateFlow<SenderState> = controller.state
    val push: StateFlow<PushSessionState?> = dmc.session
    val lan: StateFlow<LanInfo?> = network.lan
    val devices: StateFlow<List<DiscoveredDevice>> = discovery.devices

    private val _attempt = MutableStateFlow<ElderAttempt?>(null)
    val attempt: StateFlow<ElderAttempt?> = _attempt.asStateFlow()

    private val _searchExpired = MutableStateFlow(false)

    val targets: StateFlow<List<ElderTargetUi>> = combine(
        settingsRepo.settings, recent.devices, dlna.records, discovery.devices, _searchExpired,
    ) { s, r, d, found, expired ->
        ElderTargets.resolve(s.elderTargets, r, d).map { t ->
            val dev = ElderTargets.match(t, found)
            ElderTargetUi(
                t,
                found = if (dev != null) true else if (expired) false else null,
                canMirror = t.wscId != null,
                canPush = t.udn != null || dev?.canPush == true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** E04：此刻在线、且不需要再配对的设备（可信 + 推送过），不含首页已有的电视。 */
    val others: StateFlow<List<ElderOtherDevice>> = combine(discovery.devices, trusted.devices, dlna.records, targets) { found, tr, rec, home ->
        val trustedIds = tr.map { it.deviceId }.toSet()
        val confirmed = rec.filter { it.confirmedAt != null }.map { it.udn }.toSet()
        val homeKeys = home.flatMap { listOfNotNull(it.target.wscId, it.target.udn) }.toSet()
        found.mapNotNull { d ->
            val mirror = d.canMirror && d.deviceId in trustedIds
            val pushOk = d.dlna?.udn?.let { it in confirmed } == true
            if (!mirror && !pushOk) return@mapNotNull null
            if (d.deviceId in homeKeys || d.dlna?.udn in homeKeys) return@mapNotNull null
            ElderOtherDevice(d, mirror, pushOk)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var attemptJob: Job? = null
    private var searchJob: Job? = null

    fun startDiscovery() {
        discovery.start(this)
        _searchExpired.value = false
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_EXPIRE_MS)
            _searchExpired.value = true
        }
    }

    fun stopDiscovery() = discovery.stop(this)

    // ------------------------------------------------------------------ 镜像

    /**
     * 点“投屏到 X”（或图块）：找电视最多 8 秒（发现结果里的新地址优先；没发现时最后用最近记录的旧地址试一次），
     * 之后走正常的认证 → E05 预演 → 系统录屏授权。
     */
    fun cast(t: ElderHomeTarget, entry: String) {
        val id = t.wscId ?: return
        WscLog.i(TAG, "elder cast: entry=$entry pinned=${t.pinned}")
        attemptJob?.cancel()
        if (lan.value == null) {
            _attempt.value = ElderAttempt(t.label, finding = false, outcome = ElderOutcome.NO_WIFI)
            return
        }
        _attempt.value = ElderAttempt(t.label)
        discovery.start(this)
        attemptJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val dev = discovery.devices.value.firstOrNull { it.deviceId == id && it.canMirror }
                ?: withTimeoutOrNull(FIND_MS) { discovery.devices.first { l -> l.any { it.deviceId == id && it.canMirror } }.first { it.deviceId == id && it.canMirror } }
            val r = recent.find(id)
            val fp = dev?.fingerprintHex ?: r?.fingerprint ?: trusted.findById(id)?.fingerprintHex
            WscLog.i(TAG, "elder find: found=${dev != null} in ${System.currentTimeMillis() - started}ms")
            val target = when {
                dev != null -> CastTarget(id, dev.name, dev.host, dev.port, dev.sources.firstOrNull() ?: DiscoverySource.MDNS, fingerprintHex = fp, fingerprintPrefix = dev.fingerprintPrefix)
                r != null -> CastTarget(id, r.name, r.host, r.port, DiscoverySource.RECENT, fingerprintHex = r.fingerprint)
                else -> null
            }
            if (target == null) {
                _attempt.value = ElderAttempt(t.label, finding = false, outcome = ElderOutcome.NOT_FOUND)
                return@launch
            }
            _attempt.value = ElderAttempt(t.label, finding = false)
            controller.connect(target)
        }
    }

    /** 失败卡 / 结束页由会话结束触发：会话结束后由界面调用，复位控制器状态。 */
    fun acknowledge() = controller.acknowledge()

    /** 冷启动时等首页目标第一次真正算出来（图块入口，PM P1-6）；最多 3 秒。 */
    suspend fun awaitTargets(): List<ElderTargetUi> =
        withTimeoutOrNull(TARGETS_WAIT_MS) { targets.first { it.isNotEmpty() } } ?: targets.value

    fun cancelAttempt() {
        attemptJob?.cancel()
        _attempt.value = null
        val s = controller.state.value
        if (s !is SenderState.Idle && !s.isTerminal) controller.cancel()
    }

    fun clearAttempt() {
        _attempt.value = null
    }

    fun showFailure(label: String, outcome: ElderOutcome, photos: Boolean = false) {
        _attempt.value = ElderAttempt(label, finding = false, outcome = outcome, photos = photos)
    }

    fun submitPin(pin: String) = controller.submitPin(pin)
    fun stopCasting() = controller.stop()
    fun onCaptureDenied() = controller.onCaptureDenied()

    // ------------------------------------------------------------------ 推送（放照片和视频）

    /**
     * 放照片和视频：找能推送的目标（最多 8 秒），期间显示“正在连接…”并防止重复点击（UX P0-2）。
     * 电视找到了但不能推送 → CANNOT_PUSH；没找到 → 只能推送的电视用 PUSH_NOT_FOUND 的检查项（UX P1-10）。
     */
    fun findPushTarget(t: ElderHomeTarget, onFound: (PushTarget) -> Unit) {
        if (attemptJob?.isActive == true) return
        if (lan.value == null) {
            _attempt.value = ElderAttempt(t.label, finding = false, outcome = ElderOutcome.NO_WIFI, photos = true)
            return
        }
        fun pick(list: List<DiscoveredDevice>) = ElderTargets.match(t, list)?.takeIf { it.canPush }
        _attempt.value = ElderAttempt(t.label, photos = true)
        discovery.start(this)
        attemptJob = viewModelScope.launch {
            val dev = pick(discovery.devices.value) ?: withTimeoutOrNull(FIND_MS) { discovery.devices.first { pick(it) != null }.let(::pick) }
            val pt = dev?.let { targetFor(it) }
            if (pt != null) {
                _attempt.value = null
                onFound(pt)
                return@launch
            }
            val seen = ElderTargets.match(t, discovery.devices.value) != null
            WscLog.i(TAG, "elder photos: target not pushable (seen=$seen)")
            _attempt.value = ElderAttempt(
                t.label, finding = false, photos = true,
                outcome = when {
                    seen -> ElderOutcome.CANNOT_PUSH
                    t.wscId == null -> ElderOutcome.PUSH_NOT_FOUND
                    else -> ElderOutcome.NOT_FOUND
                },
            )
        }
    }

    fun targetFor(d: DiscoveredDevice): PushTarget? = d.dlna?.let {
        PushTarget(description = it.description, server = it.server, sink = it.sink, discoveryPath = d.sources.firstOrNull()?.name)
    }

    // ------------------------------------------------------------------ 请家人帮忙（E12）

    fun helpFacts(label: String?, outcome: ElderOutcome): ElderHelpText.Facts {
        val (title, detail) = ElderCopy.failure(outcome, label ?: "电视", Build.VERSION.SDK_INT, java.util.Locale.getDefault().language == "zh")
        val found = discovery.devices.value
        val multicast = if (found.isEmpty()) false else found.any { DiscoverySource.MDNS in it.sources || DiscoverySource.SSDP in it.sources }
        val t = targets.value.firstOrNull { it.target.label == label }
        val kind = t?.let { if (it.canMirror) "本应用接收端" else "电视自带投屏" }
        return ElderHelpText.Facts(
            reasonTitle = title, reasonDetail = detail,
            phoneModel = Build.MODEL.orEmpty(), androidVersion = Build.VERSION.RELEASE.orEmpty(),
            tvLabel = label, tvKind = kind, problem = ElderCopy.problem(outcome),
            hasLan = lan.value != null, multicastReply = if (lan.value == null) null else multicast,
            tvReachable = t?.found,
            time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()),
        )
    }

    fun onHelpOpened(sent: Boolean) = WscLog.i(TAG, "elder help: opened, sent=$sent")

    // ------------------------------------------------------------------ 向导（E03）与退出（E11）

    /**
     * 向导候选：最近使用 + 可信设备 + 推送过的电视（只列不需要再配对的），由存储驱动（PM P2-21 ①）。
     * 合并规则（EMU-F1）：本应用接收端的 DLNA 记录按 wsc:id（或发现结果里同一台的 UDN、或同名）并进它的镜像条目；
     * 同名的多条镜像身份（接收端重装过，旧指纹已失效）只保留最近用过的一条。
     */
    val candidates: StateFlow<List<ElderCandidate>> = combine(recent.devices, trusted.devices, dlna.records, discovery.devices) { rs, tr, recs, found ->
        mergeCandidates(rs, tr, recs, found)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, mergeCandidates(recent.devices.value, trusted.devices.value, dlna.records.value, emptyList()))

    fun finishWizard(targets: List<ElderTarget>, onDone: () -> Unit) = viewModelScope.launch {
        WscLog.i(TAG, "elder mode enabled: pinned=${targets.size}")
        settingsRepo.enableElderMode(targets)
        onDone()
    }

    fun setAudioPreference(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAudioEnabled(enabled) }

    fun exitElderMode(onDone: () -> Unit) = viewModelScope.launch {
        WscLog.i(TAG, "elder mode disabled")
        settingsRepo.disableElderMode()
        onDone()
    }

    override fun onCleared() {
        discovery.stop(this)
        super.onCleared()
    }

    companion object {
        /** 纯逻辑，单测覆盖。 */
        fun mergeCandidates(
            rs: List<com.benchan.wireless.screencasting.core.data.devices.RecentDevice>,
            tr: List<com.benchan.wireless.screencasting.core.security.trust.TrustedDevice>,
            recs: List<com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord>,
            found: List<DiscoveredDevice>,
        ): List<ElderCandidate> {
            val trustedIds = tr.map { it.deviceId }.toSet()
            val wsc = LinkedHashMap<String, ElderCandidate>()
            rs.filter { it.deviceId in trustedIds }.forEach { r ->
                wsc[r.deviceId] = ElderCandidate(ElderTarget(wscId = r.deviceId, name = r.name), true, canMirror = true, canPush = false, lastUsedAt = r.lastUsedAt)
            }
            tr.forEach { t -> wsc.getOrPut(t.deviceId) { ElderCandidate(ElderTarget(wscId = t.deviceId, name = t.name), true, canMirror = true, canPush = false, lastUsedAt = t.lastSeenAt) } }
            // 同名只留最近用过的一条
            val byName = wsc.values.groupBy { it.target.name }.mapValues { (_, l) -> l.maxByOrNull { it.lastUsedAt }!! }
            val merged = LinkedHashMap<String, ElderCandidate>()
            byName.values.sortedByDescending { it.lastUsedAt }.forEach { merged[it.target.wscId!!] = it }
            val pushOnly = mutableListOf<ElderCandidate>()
            recs.filter { it.confirmedAt != null && it.lastUsedAt > 0 }.forEach { d ->
                val linkId = d.wscId?.takeIf { it in merged }
                    ?: found.firstOrNull { it.dlna?.udn == d.udn && it.deviceId in merged }?.deviceId
                    ?: merged.values.singleOrNull { it.target.name == d.name }?.target?.wscId
                if (linkId != null) {
                    val c = merged.getValue(linkId)
                    merged[linkId] = c.copy(target = c.target.copy(udn = d.udn), canPush = true, lastUsedAt = maxOf(c.lastUsedAt, d.lastUsedAt))
                } else if (d.wscId == null || d.wscId !in wsc) {
                    pushOnly += ElderCandidate(ElderTarget(udn = d.udn, name = d.name), false, canMirror = false, canPush = true, lastUsedAt = d.lastUsedAt)
                }
            }
            return (merged.values + pushOnly.distinctBy { it.target.name }).sortedByDescending { it.lastUsedAt }
        }

        private const val TAG = "Elder"
        private const val TARGETS_WAIT_MS = 3_000L
        private const val FIND_MS = 8_000L
        private const val SEARCH_EXPIRE_MS = 10_000L
    }
}
