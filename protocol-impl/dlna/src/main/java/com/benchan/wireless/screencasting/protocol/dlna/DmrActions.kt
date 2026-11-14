package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.core.common.WscLog
import java.net.URI

/**
 * DMR 动作处理（技术方案 3.7 DMR 范围表）：AVTransport / RenderingControl / ConnectionManager。
 * 只接受 http/https 媒体地址（播放用户推送的网址是零外联的唯一例外，PRD 8.5）。
 */
class DmrActions(
    private val player: MediaRendererPlayer,
    private val consent: () -> DlnaConsent?,
) {
    suspend fun handle(service: DlnaService, req: SoapRequest, remote: String, userAgent: String?): Map<String, String> {
        return when (service) {
            DlnaService.AV_TRANSPORT -> avTransport(req, remote, userAgent)
            DlnaService.RENDERING_CONTROL -> renderingControl(req)
            DlnaService.CONNECTION_MANAGER -> connectionManager(req)
        }
    }

    private suspend fun avTransport(req: SoapRequest, remote: String, userAgent: String?): Map<String, String> {
        val a = req.args
        val snap = player.snapshot.value
        return when (req.action) {
            "SetAVTransportURI" -> {
                val uri = a["CurrentURI"]?.trim().orEmpty()
                val scheme = runCatching { URI(uri).scheme?.lowercase() }.getOrNull()
                if (scheme != "http" && scheme != "https") throw SoapException(UpnpError.RESOURCE_NOT_FOUND, "unsupported uri")
                val metaXml = a["CurrentURIMetaData"].orEmpty()
                val meta = MediaMetadata.parse(metaXml)
                val c = consent()
                if (c != null && !c.allow(remote, userAgent, meta?.title)) throw SoapException(UpnpError.ACCESS_DENIED)
                WscLog.i(TAG, "SetAVTransportURI from $remote (${meta?.mimeType ?: "?"})") // 不记录推送的网址（PRD 14）
                player.load(uri, metaXml, meta)
                emptyMap()
            }
            "SetNextAVTransportURI" -> emptyMap()
            "Play" -> {
                if (snap.state == TransportState.NO_MEDIA_PRESENT) throw SoapException(UpnpError.TRANSITION_NOT_AVAILABLE)
                player.play(); emptyMap()
            }
            "Pause" -> { player.pause(); emptyMap() }
            "Stop" -> { player.stop(); emptyMap() }
            "Seek" -> {
                val unit = a["Unit"].orEmpty()
                if (unit != "REL_TIME" && unit != "ABS_TIME") throw SoapException(UpnpError.UNSUPPORTED_SEEK_MODE)
                val target = a["Target"]?.let(UpnpTime::parse) ?: throw SoapException(UpnpError.ILLEGAL_SEEK_TARGET)
                player.seek(target); emptyMap()
            }
            "GetTransportInfo" -> mapOf(
                "CurrentTransportState" to snap.state.name,
                "CurrentTransportStatus" to if (snap.error) "ERROR_OCCURRED" else "OK",
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> {
                val pos = player.positionMs()
                mapOf(
                    "Track" to if (snap.uri.isEmpty()) "0" else "1",
                    "TrackDuration" to UpnpTime.format(snap.durationMs),
                    "TrackMetaData" to snap.metadataXml,
                    "TrackURI" to snap.uri,
                    "RelTime" to UpnpTime.format(pos),
                    "AbsTime" to UpnpTime.format(pos),
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                )
            }
            "GetMediaInfo" -> mapOf(
                "NrTracks" to if (snap.uri.isEmpty()) "0" else "1",
                "MediaDuration" to UpnpTime.format(snap.durationMs),
                "CurrentURI" to snap.uri,
                "CurrentURIMetaData" to snap.metadataXml,
                "NextURI" to "", "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> mapOf("Actions" to currentActions(snap.state))
            "GetDeviceCapabilities" -> mapOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
            else -> throw SoapException(UpnpError.INVALID_ACTION)
        }
    }

    private suspend fun renderingControl(req: SoapRequest): Map<String, String> {
        val snap = player.snapshot.value
        return when (req.action) {
            "GetVolume" -> mapOf("CurrentVolume" to snap.volume.toString())
            "SetVolume" -> {
                val v = req.args["DesiredVolume"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: throw SoapException(UpnpError.INVALID_ARGS)
                player.setVolume(v); emptyMap()
            }
            "GetMute" -> mapOf("CurrentMute" to if (snap.muted) "1" else "0")
            "SetMute" -> {
                val m = when (req.args["DesiredMute"]?.trim()?.lowercase()) {
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> throw SoapException(UpnpError.INVALID_ARGS)
                }
                player.setMute(m); emptyMap()
            }
            "ListPresets" -> mapOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> emptyMap()
            else -> throw SoapException(UpnpError.INVALID_ACTION)
        }
    }

    private fun connectionManager(req: SoapRequest): Map<String, String> = when (req.action) {
        "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to DlnaDescriptions.SINK_PROTOCOLS)
        "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> mapOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
        )
        else -> throw SoapException(UpnpError.INVALID_ACTION)
    }

    companion object {
        private const val TAG = "DmrActions"

        fun currentActions(state: TransportState): String = when (state) {
            TransportState.PLAYING -> "Pause,Stop,Seek"
            TransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek"
            TransportState.STOPPED -> "Play,Seek"
            TransportState.TRANSITIONING -> "Stop"
            TransportState.NO_MEDIA_PRESENT -> ""
        }
    }
}
