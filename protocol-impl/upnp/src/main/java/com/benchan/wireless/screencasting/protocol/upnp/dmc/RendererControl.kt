package com.benchan.wireless.screencasting.protocol.upnp.dmc

import com.benchan.wireless.screencasting.protocol.upnp.RendererDescription
import com.benchan.wireless.screencasting.protocol.upnp.SoapOutcome
import com.benchan.wireless.screencasting.protocol.upnp.UpnpError
import com.benchan.wireless.screencasting.protocol.upnp.UpnpTime
import com.benchan.wireless.screencasting.protocol.upnp.http.UpnpHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RendererService { AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER }

/** 对一台 DMR 发 SOAP 的端口（生产用 [HttpRendererPort]，单测用假实现）。 */
fun interface RendererPort {
    suspend fun call(service: RendererService, action: String, args: List<Pair<String, String>>, timeoutMs: Long): SoapOutcome
}

class HttpRendererPort(private val client: UpnpHttpClient, private val desc: RendererDescription) : RendererPort {
    override suspend fun call(service: RendererService, action: String, args: List<Pair<String, String>>, timeoutMs: Long): SoapOutcome {
        val ep = when (service) {
            RendererService.AV_TRANSPORT -> desc.avTransport
            RendererService.RENDERING_CONTROL -> desc.renderingControl
            RendererService.CONNECTION_MANAGER -> desc.connectionManager
        } ?: return SoapOutcome.Fault(UpnpError.INVALID_ACTION.code, "service not present", 0)
        return client.soap(ep.controlUrl, ep.serviceType, action, args, timeoutMs)
    }
}

/** 规范化后的 TransportState（厂商自定义值映射到最接近的标准值）。 */
enum class TransportStateValue {
    STOPPED, PLAYING, PAUSED, TRANSITIONING, NO_MEDIA, UNKNOWN;

    val isStopped: Boolean get() = this == STOPPED || this == NO_MEDIA

    companion object {
        fun of(raw: String?): TransportStateValue = when (raw?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED", "PAUSED_RECORDING" -> PAUSED
            "STOPPED" -> STOPPED
            "TRANSITIONING", "BUFFERING", "LOADING", "PREPARING" -> TRANSITIONING
            "NO_MEDIA_PRESENT", "NO_MEDIA" -> NO_MEDIA
            else -> UNKNOWN
        }
    }
}

data class TransportInfo(val state: TransportStateValue, val error: Boolean) {
    companion object {
        fun from(v: Map<String, String>) = TransportInfo(
            TransportStateValue.of(v["CurrentTransportState"]),
            v["CurrentTransportStatus"]?.trim()?.equals("ERROR_OCCURRED", ignoreCase = true) == true,
        )
    }
}

data class PositionInfo(val trackUri: String?, val positionMs: Long?, val durationMs: Long?) {
    companion object {
        fun from(v: Map<String, String>) = PositionInfo(
            trackUri = v["TrackURI"]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NOT_IMPLEMENTED", true) },
            positionMs = (v["RelTime"]?.let(UpnpTime::parse) ?: v["AbsTime"]?.let(UpnpTime::parse)),
            durationMs = v["TrackDuration"]?.let(UpnpTime::parse)?.takeIf { it > 0 },
        )
    }
}

/**
 * 类型化的 AVTransport / RenderingControl / ConnectionManager 调用。同一台电视的所有调用串行（很多电视不能并发处理请求，
 * 技术方案 3.13.3.1）。Mutex 公平排队，用户操作不会被饿死。
 */
class RendererControl(private val port: RendererPort, private val timings: PushTimings = PushTimings()) {
    private val mutex = Mutex()

    private suspend fun call(svc: RendererService, action: String, args: List<Pair<String, String>>, timeout: Long = timings.controlTimeoutMs) =
        mutex.withLock { port.call(svc, action, args, timeout) }

    suspend fun setUri(url: String, metadata: String, timeoutMs: Long = timings.loadTimeoutMs) =
        call(RendererService.AV_TRANSPORT, "SetAVTransportURI", listOf(I0, "CurrentURI" to url, "CurrentURIMetaData" to metadata), timeoutMs)

    suspend fun play() = call(RendererService.AV_TRANSPORT, "Play", listOf(I0, "Speed" to "1"), timings.loadTimeoutMs)
    suspend fun pause() = call(RendererService.AV_TRANSPORT, "Pause", listOf(I0))
    suspend fun stop(timeoutMs: Long = timings.controlTimeoutMs) = call(RendererService.AV_TRANSPORT, "Stop", listOf(I0), timeoutMs)
    suspend fun seek(unit: SeekUnit, positionMs: Long) =
        call(RendererService.AV_TRANSPORT, "Seek", listOf(I0, "Unit" to unit.name, "Target" to UpnpTime.format(positionMs)))

    suspend fun transportInfo() = call(RendererService.AV_TRANSPORT, "GetTransportInfo", listOf(I0))
    suspend fun positionInfo() = call(RendererService.AV_TRANSPORT, "GetPositionInfo", listOf(I0))
    suspend fun mediaInfo() = call(RendererService.AV_TRANSPORT, "GetMediaInfo", listOf(I0))
    suspend fun transportActions() = call(RendererService.AV_TRANSPORT, "GetCurrentTransportActions", listOf(I0))

    suspend fun getVolume() = call(RendererService.RENDERING_CONTROL, "GetVolume", listOf(I0, MASTER))
    suspend fun setVolume(v: Int) = call(RendererService.RENDERING_CONTROL, "SetVolume", listOf(I0, MASTER, "DesiredVolume" to v.toString()))
    suspend fun getMute() = call(RendererService.RENDERING_CONTROL, "GetMute", listOf(I0, MASTER))
    suspend fun setMute(m: Boolean) = call(RendererService.RENDERING_CONTROL, "SetMute", listOf(I0, MASTER, "DesiredMute" to if (m) "1" else "0"))

    suspend fun protocolInfo() = call(RendererService.CONNECTION_MANAGER, "GetProtocolInfo", emptyList())

    private companion object {
        val I0 = "InstanceID" to "0"
        val MASTER = "Channel" to "Master"
    }
}
