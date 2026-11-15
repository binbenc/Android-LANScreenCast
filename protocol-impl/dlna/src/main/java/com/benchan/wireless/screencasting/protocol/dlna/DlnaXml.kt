package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.protocol.upnp.Xml

/** UPnP 服务类型与路径。 */
enum class DlnaService(val id: String, val type: String, val path: String) {
    AV_TRANSPORT("urn:upnp-org:serviceId:AVTransport", "urn:schemas-upnp-org:service:AVTransport:1", "avt"),
    RENDERING_CONTROL("urn:upnp-org:serviceId:RenderingControl", "urn:schemas-upnp-org:service:RenderingControl:1", "rc"),
    CONNECTION_MANAGER("urn:upnp-org:serviceId:ConnectionManager", "urn:schemas-upnp-org:service:ConnectionManager:1", "cm");

    val scpdUrl: String get() = "/dlna/$path/scpd.xml"
    val controlUrl: String get() = "/dlna/$path/control"
    val eventUrl: String get() = "/dlna/$path/event"

    companion object {
        fun byPath(p: String): DlnaService? = entries.firstOrNull { it.path == p }
    }
}

/** 设备描述与 SCPD（技术方案 3.7：静态模板）。 */
object DlnaDescriptions {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** Sink 协议列表（GetProtocolInfo）：ExoPlayer 能播的常见格式。 */
    val SINK_PROTOCOLS = listOf(
        "http-get:*:video/mp4:*", "http-get:*:video/x-matroska:*", "http-get:*:video/webm:*", "http-get:*:video/mpeg:*",
        "http-get:*:video/mp2t:*", "http-get:*:video/quicktime:*", "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/x-mpegurl:*", "http-get:*:application/dash+xml:*", "http-get:*:video/x-flv:*",
        "http-get:*:audio/mpeg:*", "http-get:*:audio/mp4:*", "http-get:*:audio/aac:*", "http-get:*:audio/flac:*",
        "http-get:*:audio/x-flac:*", "http-get:*:audio/ogg:*", "http-get:*:audio/wav:*", "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*", "http-get:*:*:*",
    ).joinToString(",")

    /**
     * 设备描述。附带 WSC 自定义节点（技术方案 3.6：mDNS 失效时发送端可从 SSDP/描述里拿到 WSC 入口）。
     */
    fun device(friendlyName: String, udn: String, wscPort: Int?, wscId: String?, fpPrefix: String?): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0" xmlns:wsc="urn:wsc:device-1-0">""")
        append("<specVersion><major>1</major><minor>0</minor></specVersion><device>")
        append("<deviceType>$DEVICE_TYPE</deviceType>")
        append("<friendlyName>${Xml.escape(friendlyName)}</friendlyName>")
        append("<manufacturer>WirelessScreenCasting</manufacturer>")
        append("<manufacturerURL>https://github.com/</manufacturerURL>")
        append("<modelDescription>LAN screen casting receiver (GPL-3.0)</modelDescription>")
        append("<modelName>WSC Renderer</modelName><modelNumber>1</modelNumber>")
        append("<UDN>uuid:$udn</UDN>")
        append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>")
        if (wscPort != null && wscId != null) {
            append("<wsc:service><wsc:port>$wscPort</wsc:port><wsc:id>${Xml.escape(wscId)}</wsc:id><wsc:fp>${Xml.escape(fpPrefix.orEmpty())}</wsc:fp></wsc:service>")
        }
        append("<serviceList>")
        for (s in DlnaService.entries) {
            append("<service><serviceType>${s.type}</serviceType><serviceId>${s.id}</serviceId>")
            append("<SCPDURL>${s.scpdUrl}</SCPDURL><controlURL>${s.controlUrl}</controlURL><eventSubURL>${s.eventUrl}</eventSubURL></service>")
        }
        append("</serviceList></device></root>")
    }

    private data class Arg(val name: String, val dir: String, val related: String)
    private data class Action(val name: String, val args: List<Arg>)
    private data class Var(val name: String, val type: String, val events: Boolean = false, val allowed: List<String> = emptyList())

    private fun inArgs(vararg a: Pair<String, String>) = a.map { Arg(it.first, "in", it.second) }
    private fun outArgs(vararg a: Pair<String, String>) = a.map { Arg(it.first, "out", it.second) }

    private val avtActions = listOf(
        Action("SetAVTransportURI", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "CurrentURI" to "AVTransportURI", "CurrentURIMetaData" to "AVTransportURIMetaData")),
        Action("SetNextAVTransportURI", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "NextURI" to "NextAVTransportURI", "NextURIMetaData" to "NextAVTransportURIMetaData")),
        Action("Play", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "Speed" to "TransportPlaySpeed")),
        Action("Pause", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID")),
        Action("Stop", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID")),
        Action("Seek", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "Unit" to "A_ARG_TYPE_SeekMode", "Target" to "A_ARG_TYPE_SeekTarget")),
        Action("GetTransportInfo", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("CurrentTransportState" to "TransportState", "CurrentTransportStatus" to "TransportStatus", "CurrentSpeed" to "TransportPlaySpeed")),
        Action("GetPositionInfo", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("Track" to "CurrentTrack", "TrackDuration" to "CurrentTrackDuration", "TrackMetaData" to "CurrentTrackMetaData", "TrackURI" to "CurrentTrackURI", "RelTime" to "RelativeTimePosition", "AbsTime" to "AbsoluteTimePosition", "RelCount" to "RelativeCounterPosition", "AbsCount" to "AbsoluteCounterPosition")),
        Action("GetMediaInfo", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("NrTracks" to "NumberOfTracks", "MediaDuration" to "CurrentMediaDuration", "CurrentURI" to "AVTransportURI", "CurrentURIMetaData" to "AVTransportURIMetaData", "NextURI" to "NextAVTransportURI", "NextURIMetaData" to "NextAVTransportURIMetaData", "PlayMedium" to "PlaybackStorageMedium", "RecordMedium" to "RecordStorageMedium", "WriteStatus" to "RecordMediumWriteStatus")),
        Action("GetTransportSettings", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("PlayMode" to "CurrentPlayMode", "RecQualityMode" to "CurrentRecordQualityMode")),
        Action("GetCurrentTransportActions", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("Actions" to "CurrentTransportActions")),
        Action("GetDeviceCapabilities", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("PlayMedia" to "PossiblePlaybackStorageMedia", "RecMedia" to "PossibleRecordStorageMedia", "RecQualityModes" to "PossibleRecordQualityModes")),
    )
    private val avtVars = listOf(
        Var("TransportState", "string", false, listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")),
        Var("TransportStatus", "string", false, listOf("OK", "ERROR_OCCURRED")),
        Var("TransportPlaySpeed", "string", false, listOf("1")),
        Var("LastChange", "string", true),
        Var("AVTransportURI", "string"), Var("AVTransportURIMetaData", "string"), Var("NextAVTransportURI", "string"),
        Var("NextAVTransportURIMetaData", "string"), Var("CurrentTrack", "ui4"), Var("CurrentTrackDuration", "string"),
        Var("CurrentTrackMetaData", "string"), Var("CurrentTrackURI", "string"), Var("RelativeTimePosition", "string"),
        Var("AbsoluteTimePosition", "string"), Var("RelativeCounterPosition", "i4"), Var("AbsoluteCounterPosition", "i4"),
        Var("NumberOfTracks", "ui4"), Var("CurrentMediaDuration", "string"), Var("PlaybackStorageMedium", "string"),
        Var("RecordStorageMedium", "string"), Var("RecordMediumWriteStatus", "string"), Var("CurrentPlayMode", "string", false, listOf("NORMAL")),
        Var("CurrentRecordQualityMode", "string"), Var("CurrentTransportActions", "string"), Var("PossiblePlaybackStorageMedia", "string"),
        Var("PossibleRecordStorageMedia", "string"), Var("PossibleRecordQualityModes", "string"),
        Var("A_ARG_TYPE_InstanceID", "ui4"), Var("A_ARG_TYPE_SeekMode", "string", false, listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
        Var("A_ARG_TYPE_SeekTarget", "string"),
    )
    private val rcActions = listOf(
        Action("GetVolume", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "Channel" to "A_ARG_TYPE_Channel") + outArgs("CurrentVolume" to "Volume")),
        Action("SetVolume", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "Channel" to "A_ARG_TYPE_Channel", "DesiredVolume" to "Volume")),
        Action("GetMute", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "Channel" to "A_ARG_TYPE_Channel") + outArgs("CurrentMute" to "Mute")),
        Action("SetMute", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "Channel" to "A_ARG_TYPE_Channel", "DesiredMute" to "Mute")),
        Action("ListPresets", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID") + outArgs("CurrentPresetNameList" to "PresetNameList")),
        Action("SelectPreset", inArgs("InstanceID" to "A_ARG_TYPE_InstanceID", "PresetName" to "A_ARG_TYPE_PresetName")),
    )
    private val rcVars = listOf(
        Var("LastChange", "string", true), Var("Volume", "ui2"), Var("Mute", "boolean"), Var("PresetNameList", "string"),
        Var("A_ARG_TYPE_InstanceID", "ui4"), Var("A_ARG_TYPE_Channel", "string", false, listOf("Master")),
        Var("A_ARG_TYPE_PresetName", "string", false, listOf("FactoryDefaults")),
    )
    private val cmActions = listOf(
        Action("GetProtocolInfo", outArgs("Source" to "SourceProtocolInfo", "Sink" to "SinkProtocolInfo")),
        Action("GetCurrentConnectionIDs", outArgs("ConnectionIDs" to "CurrentConnectionIDs")),
        Action("GetCurrentConnectionInfo", inArgs("ConnectionID" to "A_ARG_TYPE_ConnectionID") + outArgs(
            "RcsID" to "A_ARG_TYPE_RcsID", "AVTransportID" to "A_ARG_TYPE_AVTransportID", "ProtocolInfo" to "A_ARG_TYPE_ProtocolInfo",
            "PeerConnectionManager" to "A_ARG_TYPE_ConnectionManager", "PeerConnectionID" to "A_ARG_TYPE_ConnectionID",
            "Direction" to "A_ARG_TYPE_Direction", "Status" to "A_ARG_TYPE_ConnectionStatus",
        )),
    )
    private val cmVars = listOf(
        Var("SourceProtocolInfo", "string", true), Var("SinkProtocolInfo", "string", true), Var("CurrentConnectionIDs", "string", true),
        Var("A_ARG_TYPE_ConnectionStatus", "string", false, listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
        Var("A_ARG_TYPE_ConnectionManager", "string"), Var("A_ARG_TYPE_Direction", "string", false, listOf("Input", "Output")),
        Var("A_ARG_TYPE_ProtocolInfo", "string"), Var("A_ARG_TYPE_ConnectionID", "i4"), Var("A_ARG_TYPE_AVTransportID", "i4"), Var("A_ARG_TYPE_RcsID", "i4"),
    )

    fun scpd(service: DlnaService): String {
        val (actions, vars) = when (service) {
            DlnaService.AV_TRANSPORT -> avtActions to avtVars
            DlnaService.RENDERING_CONTROL -> rcActions to rcVars
            DlnaService.CONNECTION_MANAGER -> cmActions to cmVars
        }
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>""")
            for (a in actions) {
                append("<action><name>${a.name}</name><argumentList>")
                for (arg in a.args) append("<argument><name>${arg.name}</name><direction>${arg.dir}</direction><relatedStateVariable>${arg.related}</relatedStateVariable></argument>")
                append("</argumentList></action>")
            }
            append("</actionList><serviceStateTable>")
            for (v in vars) {
                append("""<stateVariable sendEvents="${if (v.events) "yes" else "no"}"><name>${v.name}</name><dataType>${v.type}</dataType>""")
                if (v.allowed.isNotEmpty()) append("<allowedValueList>" + v.allowed.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>")
                if (v.name == "Volume") append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>")
                append("</stateVariable>")
            }
            append("</serviceStateTable></scpd>")
        }
    }
}
