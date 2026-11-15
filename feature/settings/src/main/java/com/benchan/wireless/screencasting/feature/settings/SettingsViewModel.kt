package com.benchan.wireless.screencasting.feature.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benchan.wireless.screencasting.core.data.log.DiagnosticLog
import com.benchan.wireless.screencasting.core.data.report.ReportSummary
import com.benchan.wireless.screencasting.core.data.report.SessionReportStore
import com.benchan.wireless.screencasting.core.data.settings.AppMode
import com.benchan.wireless.screencasting.core.data.settings.AppSettings
import com.benchan.wireless.screencasting.core.data.settings.ConnectionPolicy
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.discovery.diagnostics.DiagnosticCheck
import com.benchan.wireless.screencasting.core.discovery.diagnostics.NetworkDiagnostics
import com.benchan.wireless.screencasting.core.net.InetUtils
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.security.trust.TrustedDevice
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import com.benchan.wireless.screencasting.protocol.airplay.api.AirPlayReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val trustedStore: TrustedDeviceStore,
    private val dlnaStore: com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore,
    private val reports: SessionReportStore,
    private val log: DiagnosticLog,
    private val diagnostics: NetworkDiagnostics,
    network: com.benchan.wireless.screencasting.core.net.NetworkMonitor,
    airPlay: Optional<AirPlayReceiver>,
    private val a11y: com.benchan.wireless.screencasting.feature.control.AccessibilityBridge,
    private val gameStore: com.benchan.wireless.screencasting.core.data.game.GameModeStore,
) : ViewModel() {
    /** F18 G06：把“游戏”设为默认前，本机是否已确认过 G01 首次说明卡。 */
    fun gameIntroAcknowledged(): Boolean = gameStore.introAcknowledged

    /** G01 “开启”后保存默认档为“游戏”（从设置进来只保存默认档，不切换当前会话）。 */
    fun acknowledgeGameIntroAndSetGame() = viewModelScope.launch {
        gameStore.acknowledgeIntro()
        repo.setQuality(QualityProfile.GAME)
    }

    /** 反向控制的无障碍服务是否已在系统设置中开启（09A 行状态值）。 */
    fun remoteControlServiceOn(): Boolean = a11y.isServiceEnabledInSettings()

    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val trusted: StateFlow<List<TrustedDevice>> = trustedStore.devices

    /** 推送过（或确认过）的 DLNA 电视（交互稿 P02 / Q14-B：可逐台忘记）。 */
    val pushedTvs: StateFlow<List<com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord>> =
        dlnaStore.records.map { list -> list.filter { it.confirmedAt != null || it.lastUsedAt > 0 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forgetPushedTv(udn: String) = dlnaStore.forget(udn)
    val lan: StateFlow<com.benchan.wireless.screencasting.core.net.LanInfo?> = network.lan

    /** 构建开关关闭时为 false，设置页隐藏“兼容 Apple 设备”（技术方案 3.8）。 */
    val appleCompatAvailable: Boolean = airPlay.isPresent

    private val _range30 = MutableStateFlow(true)
    val range30: StateFlow<Boolean> = _range30.asStateFlow()

    private val _summaryForRange = MutableStateFlow(ReportSummary.from(emptyList()))
    val summaryForRange: StateFlow<ReportSummary> = _summaryForRange.asStateFlow()

    init {
        viewModelScope.launch {
            reports.records.collect { recomputeSummary() }
        }
    }

    fun setRange30(v: Boolean) {
        _range30.value = v
        recomputeSummary()
    }

    private fun recomputeSummary() {
        val since = if (_range30.value) System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30) else null
        _summaryForRange.value = ReportSummary.from(reports.records.value, since)
    }

    fun clearReports() {
        reports.clear()
        // 清空报告时一并清掉游戏模式的实测记忆（实测延迟、“只能 30 帧”，PM 走查 P1-6 / P2-9）
        gameStore.clearReceivers()
    }

    fun setDeviceName(v: String) = viewModelScope.launch { repo.setDeviceName(v) }
    fun setMode(v: AppMode) = viewModelScope.launch { repo.setMode(v) }
    fun setQuality(v: QualityProfile) = viewModelScope.launch { repo.setQuality(v) }
    fun setAutoAdjust(v: Boolean) = viewModelScope.launch { repo.setAutoAdjust(v) }
    fun setAudio(v: Boolean) = viewModelScope.launch { repo.setAudioEnabled(v) }
    fun setPolicy(v: ConnectionPolicy) = viewModelScope.launch { repo.setConnectionPolicy(v) }
    fun setDlna(v: Boolean) = viewModelScope.launch { repo.setDlnaEnabled(v) }
    fun setDlnaConfirm(v: Boolean) = viewModelScope.launch { repo.setDlnaConfirmUnknown(v) }
    fun setAppleCompat(enabled: Boolean, pin: Boolean) = viewModelScope.launch { repo.setAppleCompat(enabled, pin) }
    fun setBootStart(v: Boolean) = viewModelScope.launch { repo.setBootStart(v) }
    fun setSkipIntro(v: Boolean) = viewModelScope.launch { repo.setSkipCaptureIntro(v) }
    fun setDirectShare(v: Boolean) = viewModelScope.launch { repo.setDirectShareEnabled(v) }
    fun removeTrusted(id: String) {
        trustedStore.remove(id)
        gameStore.removeReceiver(id)
    }

    // —— F11 网络诊断 ——
    private val _checks = MutableStateFlow<List<DiagnosticCheck>?>(null)
    val checks: StateFlow<List<DiagnosticCheck>?> = _checks.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun runDiagnostics(target: String?) = viewModelScope.launch {
        _running.value = true
        val t = target?.takeIf { it.isNotBlank() }?.let { InetUtils.parseHostPort(it, WscProtocol.PREFERRED_PORT) }
        _checks.value = runCatching { diagnostics.run(t) }.getOrElse { emptyList() }
        _running.value = false
    }

    // —— F25 本地日志：先预览后导出 ——
    suspend fun buildLogPreview(versionName: String): String = withContext(Dispatchers.IO) {
        val s = ReportSummary.from(reports.records.value)
        val records = reports.records.value.takeLast(20)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val raw = buildString {
            appendLine("# 局域投屏 本地诊断日志")
            appendLine("# 只包含本机统计与运行日志，不含 Wi‑Fi 名称、投屏内容、推送的网址；对端设备名已替换为“设备 A / B …”。")
            appendLine("app=$versionName android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) model=${Build.MODEL}")
            appendLine("sessions=${s.total} success=${s.succeeded} disconnects=${s.disconnects} avgLatency=${s.avgLatencyMs} p95=${s.p95LatencyMs}")
            appendLine()
            appendLine("## 最近会话")
            records.forEach { r ->
                appendLine(
                    "${fmt.format(Date(r.startedAt))} ${r.protocol.exportLabel()} ${r.role} peer=${r.peerName} path=${r.discoveryPath} " +
                        "firstFrame=${r.firstFrameMs}ms ok=${r.success} p50=${r.latencyP50Ms} p95=${r.latencyP95Ms} " +
                        "bitrate=${r.avgBitrateKbps}kbps drops=${r.framesDropped} disconnects=${r.disconnects}/${r.autoRecovered} codec=${r.codec} end=${r.endReason}" +
                        (r.controlledMs?.let { " controlled=${it / 1000}s" } ?: "") +
                        (r.push?.let { " " + PushExport.line(it) } ?: ""),
                )
            }
            appendLine()
            appendLine("## 运行日志")
            append(log.readAll())
        }
        // 对端设备名可能是“小张的 iPhone”之类的个人信息：导出前脱敏，同一导出内一致映射（N8）
        val names = reports.records.value.map { it.peerName } + trustedStore.devices.value.map { it.name } + dlnaStore.records.value.map { it.name }
        LogAnonymizer.anonymize(raw, names, localName = repo.current().deviceName)
    }

    /** 写到 cache/exports，由 FileProvider 分享（只共享用户预览过的这一个文件）。 */
    suspend fun writeExport(text: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "wsc-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt").apply { writeText(text) }
    }

    fun clearLog() = log.clear()
}

/** 导出日志里的协议名：界面（含导出预览）不出现 “AirPlay” 字样（PRD 12）。 */
private fun com.benchan.wireless.screencasting.core.data.report.CastProtocol.exportLabel(): String = when (this) {
    com.benchan.wireless.screencasting.core.data.report.CastProtocol.AIRPLAY -> "APPLE_COMPAT"
    else -> name
}

/** 导出日志的对端设备名脱敏（N8）：按首次出现顺序映射为“设备 A / B / …”，同一份导出内一致；会话报告界面不受影响。 */
internal object LogAnonymizer {
    private const val MIN_SUBSTRING_LEN = 3

    /**
     * @param localName 本机设备名，替换为“本机”（R4）。
     * 名字少于 3 个字符时只按整词替换（前后不是字母 / 数字 / 汉字），避免把 “TV” 之类误替换进别的单词里。
     */
    fun anonymize(text: String, peerNames: List<String>, localName: String? = null): String {
        val local = localName?.trim()?.takeIf { it.isNotEmpty() }
        val distinct = peerNames.map { it.trim() }.filter { it.isNotEmpty() && it != "DLNA" && it != local }.distinct()
        val order = distinct.sortedBy { n -> text.indexOf(n).let { if (it < 0) Int.MAX_VALUE else it } }
        val aliases = LinkedHashMap<String, String>()
        local?.let { aliases[it] = "本机" }
        order.forEachIndexed { i, n -> aliases[n] = "设备 ${alias(i)}" }
        if (aliases.isEmpty()) return text
        var out = text
        // 先替换较长的名字，避免“小张的手机”被“小张”截断
        for (n in aliases.keys.sortedByDescending { it.length }) {
            val replacement = aliases.getValue(n)
            out = if (n.length >= MIN_SUBSTRING_LEN) {
                out.replace(n, replacement)
            } else {
                Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])").replace(out, Regex.escapeReplacement(replacement))
            }
        }
        return out
    }

    private fun alias(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }
}

/**
 * DLNA 推送报告的导出字段（PRD 8.1.1 G，第 1 轮 PM P1-6）。只含报告里本来就有的字段：
 * 没有文件名、路径、标题、网址、令牌（报告本身就不存这些）。
 */
internal object PushExport {
    fun line(p: com.benchan.wireless.screencasting.core.data.report.PushReport): String = buildString {
        append("push[type=${p.contentType} src=${p.source} mime=${p.mimeTypes.joinToString("|")} size=${p.sizeBucket} items=${p.itemCount}")
        append(" switches=${p.switchCount} autoNext=${p.autoAdvanceCount} tv=${p.tvManufacturer}/${p.tvModel} server=${p.tvServer}")
        append(" upnp=${p.upnpErrorCode} http=${p.httpStatus} fallbacks=${p.fallbacks.joinToString("|")} sync=${p.syncMode} ranges=${p.rangeRequests}")
        p.transcode?.let { t ->
            append(" transcode[offered=${t.offered} at=${t.offeredAt} choice=${t.choice} src=${t.sourceContainer}/${t.sourceVideoCodec}/${t.sourceAudioCodec}")
            append(" res=${t.sourceResolution} hdr=${t.hdr} out=${t.output} ms=${t.elapsedMs} result=${t.result} hot=${t.thermalPauses} err=${t.errorCode}]")
        }
        append("]")
    }
}
