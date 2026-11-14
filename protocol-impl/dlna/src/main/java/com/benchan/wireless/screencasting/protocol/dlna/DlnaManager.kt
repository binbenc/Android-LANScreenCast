package com.benchan.wireless.screencasting.protocol.dlna

import android.content.Context
import android.os.Build
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.data.report.SessionRecord
import com.benchan.wireless.screencasting.core.data.report.SessionReportStore
import com.benchan.wireless.screencasting.core.net.LanHttpServer
import com.benchan.wireless.screencasting.core.net.MulticastLockHolder
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 正在进行的 DLNA 推送播放（接收端界面据此显示播放页）。 */
data class DlnaPlayback(val title: String?, val controlPoint: String, val state: TransportState, val startedAt: Long)

/**
 * DLNA DMR（F09、PRD R-07、技术方案 3.7）：自研最小 SSDP + SOAP + GENA，HTTP 用 Ktor CIO，播放用 Media3 ExoPlayer。
 * 只在接收端待机监听期间运行（与 WSC 同生命周期），由设置里的“DLNA 接收”开关控制。
 */
@Singleton
class DlnaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: NetworkMonitor,
    private val multicastLock: MulticastLockHolder,
    private val reports: SessionReportStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    val player = ExoMediaRendererPlayer(context)

    /** 由接收端注入：陌生控制点确认 + R-09 单路规则。 */
    @Volatile
    var consent: DlnaConsent? = null

    private val _playback = MutableStateFlow<DlnaPlayback?>(null)
    val playback: StateFlow<DlnaPlayback?> = _playback.asStateFlow()

    private var http: LanHttpServer? = null
    private var ssdp: SsdpAdvertiser? = null
    private var gena: GenaManager? = null
    private var config: Pair<String, WscAdvert?>? = null
    private var lastControlPoint = ""
    private var sessionStartedAt = 0L
    private var trackTitle: String? = null

    val udn: String by lazy {
        val f = File(context.noBackupFilesDir, "dlna_udn")
        runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { runCatching { f.writeText(it) } }
    }

    init {
        scope.launch {
            player.snapshot.collect { snap ->
                gena?.publish(DlnaService.AV_TRANSPORT) { GenaManager.lastChange(DlnaService.AV_TRANSPORT, player.snapshot.value) }
                gena?.publish(DlnaService.RENDERING_CONTROL) { GenaManager.lastChange(DlnaService.RENDERING_CONTROL, player.snapshot.value) }
                val active = snap.state == TransportState.PLAYING || snap.state == TransportState.PAUSED_PLAYBACK || snap.state == TransportState.TRANSITIONING
                val prev = _playback.value
                if (active) {
                    if (prev == null) {
                        sessionStartedAt = System.currentTimeMillis()
                        trackTitle = snap.metadata?.title
                    }
                    _playback.value = DlnaPlayback(snap.metadata?.title, lastControlPoint, snap.state, prev?.startedAt ?: sessionStartedAt)
                } else if (prev != null) {
                    _playback.value = null
                    recordSession(snap.error)
                }
            }
        }
    }

    suspend fun start(friendlyName: String, wsc: WscAdvert?) = mutex.withLock {
        val lan = network.lan.value ?: return@withLock
        if (http != null && config == (friendlyName to wsc)) return@withLock
        stopLocked()
        config = friendlyName to wsc
        val g = GenaManager(scope)
        gena = g
        val actions = DmrActions(player) {
            val c = consent ?: return@DmrActions null
            DlnaConsent { remote, ua, title ->
                lastControlPoint = remote
                c.allow(remote, ua, title)
            }
        }
        val server = LanHttpServer("dlna", preferredPort = 0) {
            dlnaModule(
                description = { DlnaDescriptions.device(friendlyName, udn, wsc?.port, wsc?.deviceId, wsc?.fingerprintPrefix) },
                actions = actions,
                gena = g,
                initialEvent = { svc ->
                    if (svc == DlnaService.CONNECTION_MANAGER) {
                        GenaManager.propertySet("SourceProtocolInfo" to "", "SinkProtocolInfo" to DlnaDescriptions.SINK_PROTOCOLS, "CurrentConnectionIDs" to "0")
                    } else {
                        GenaManager.lastChange(svc, player.snapshot.value)
                    }
                },
            )
        }
        val port = runCatching { server.start() }.getOrElse {
            WscLog.w(TAG, "DLNA http failed", it)
            return@withLock
        }
        http = server
        multicastLock.acquire(LOCK_OWNER)
        val adv = SsdpAdvertiser(lan.address, udn, "http://${lan.ip}:$port/dlna/description.xml", wsc)
        adv.start(scope)
        ssdp = adv
        WscLog.i(TAG, "DMR '$friendlyName' on :$port")
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        ssdp?.stop()
        ssdp = null
        http?.stop()
        http = null
        gena = null
        config = null
        multicastLock.release(LOCK_OWNER)
        if (_playback.value != null) player.stop()
    }

    /** 接收端界面上的“停止”（连按两次返回）。 */
    fun stopPlayback() = scope.launch { player.stop() }

    private fun recordSession(error: Boolean) {
        val start = sessionStartedAt.takeIf { it > 0 } ?: return
        runCatching {
            reports.add(
                SessionRecord(
                    sessionId = UUID.randomUUID().toString(), protocol = CastProtocol.DLNA, role = "RECEIVER",
                    peerName = lastControlPoint.ifBlank { "DLNA" }, startedAt = start, endedAt = System.currentTimeMillis(),
                    success = !error, endReason = if (error) "PLAYBACK_ERROR" else "STOPPED",
                    androidVersion = Build.VERSION.RELEASE.orEmpty(), deviceModel = Build.MODEL.orEmpty(),
                ),
            )
        }
        sessionStartedAt = 0
        trackTitle = null
    }

    private companion object {
        const val TAG = "DlnaManager"
        const val LOCK_OWNER = "dlna"
    }
}
