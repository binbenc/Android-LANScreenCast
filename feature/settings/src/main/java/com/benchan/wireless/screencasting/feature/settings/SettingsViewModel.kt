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
    private val reports: SessionReportStore,
    private val log: DiagnosticLog,
    private val diagnostics: NetworkDiagnostics,
    network: com.benchan.wireless.screencasting.core.net.NetworkMonitor,
    airPlay: Optional<AirPlayReceiver>,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val trusted: StateFlow<List<TrustedDevice>> = trustedStore.devices
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

    fun clearReports() = reports.clear()

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
    fun removeTrusted(id: String) = trustedStore.remove(id)

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
        buildString {
            appendLine("# 局域投屏 本地诊断日志")
            appendLine("# 只包含本机统计与运行日志，不含 Wi‑Fi 名称、投屏内容、推送的网址。")
            appendLine("app=$versionName android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) model=${Build.MODEL}")
            appendLine("sessions=${s.total} success=${s.succeeded} disconnects=${s.disconnects} avgLatency=${s.avgLatencyMs} p95=${s.p95LatencyMs}")
            appendLine()
            appendLine("## 最近会话")
            records.forEach { r ->
                appendLine(
                    "${fmt.format(Date(r.startedAt))} ${r.protocol} ${r.role} peer=${r.peerName} path=${r.discoveryPath} " +
                        "firstFrame=${r.firstFrameMs}ms ok=${r.success} p50=${r.latencyP50Ms} p95=${r.latencyP95Ms} " +
                        "bitrate=${r.avgBitrateKbps}kbps drops=${r.framesDropped} disconnects=${r.disconnects}/${r.autoRecovered} codec=${r.codec} end=${r.endReason}",
                )
            }
            appendLine()
            appendLine("## 运行日志")
            append(log.readAll())
        }
    }

    /** 写到 cache/exports，由 FileProvider 分享（只共享用户预览过的这一个文件）。 */
    suspend fun writeExport(text: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "wsc-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt").apply { writeText(text) }
    }

    fun clearLog() = log.clear()
}
