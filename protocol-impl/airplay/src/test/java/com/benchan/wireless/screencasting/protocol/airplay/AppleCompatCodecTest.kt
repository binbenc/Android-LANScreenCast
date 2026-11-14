package com.benchan.wireless.screencasting.protocol.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AppleCompatCodecTest {

    // —— 音量 ——

    @Test
    fun volumeMapping() {
        assertEquals(0f, AirPlayVolume.toLinear(AirPlayVolume.MUTE_DB), 0f)
        assertEquals(0f, AirPlayVolume.toLinear(-30f), 0f)
        assertEquals(0f, AirPlayVolume.toLinear(-50f), 0f)
        assertEquals(0f, AirPlayVolume.toLinear(Float.NaN), 0f)
        assertEquals(1f, AirPlayVolume.toLinear(0f), 0f)
        assertEquals(1f, AirPlayVolume.toLinear(3f), 0f)
        assertEquals(0.501f, AirPlayVolume.toLinear(-6f), 0.001f)
        assertTrue(AirPlayVolume.toLinear(-10f) < AirPlayVolume.toLinear(-5f))
    }

    // —— 音频格式 ——

    @Test
    fun audioCodecFromCt() {
        assertEquals(RaopAudioCodec.ALAC, RaopAudioCodec.fromCt(2))
        assertEquals(RaopAudioCodec.AAC_LC, RaopAudioCodec.fromCt(4))
        assertEquals(RaopAudioCodec.AAC_ELD, RaopAudioCodec.fromCt(8))
        assertEquals(RaopAudioCodec.PCM, RaopAudioCodec.fromCt(1))
        assertEquals(RaopAudioCodec.PCM, RaopAudioCodec.fromCt(99))
        assertEquals(480, RaopAudioCodec.AAC_ELD.samplesPerFrame)
        assertEquals(352, RaopAudioCodec.ALAC.samplesPerFrame)
    }

    @Test
    fun aacCodecSpecificDataMatchesUxPlay() {
        // UxPlay renderers/audio_renderer.c：AAC-LC codec_data=1210，AAC-ELD codec_data=f8e85000
        assertArrayEquals(bytes(0x12, 0x10), RaopAudioCodec.AAC_LC.csd0)
        assertArrayEquals(bytes(0xF8, 0xE8, 0x50, 0x00), RaopAudioCodec.AAC_ELD.csd0)
        assertNull(RaopAudioCodec.ALAC.csd0)
        // AudioSpecificConfig 的 audioObjectType：LC=2；ELD 用转义值 31 + 6 位扩展（32+7=39）
        assertEquals(2, (0x12 shr 3))
        assertEquals(31, (0xF8 shr 3))
    }

    // —— Annex-B ——

    private val sc4 = bytes(0, 0, 0, 1)
    private val sc3 = bytes(0, 0, 1)

    @Test
    fun h264ParameterSetsAreSplitFromIdr() {
        val sps = sc4 + bytes(0x67, 0x42, 0x00, 0x1F)
        val pps = sc4 + bytes(0x68, 0xCE, 0x38, 0x80)
        val idr = sc3 + bytes(0x65, 0x88, 0x84, 0x00, 0x00)
        val frame = sps + pps + idr
        assertEquals(listOf(0, sps.size, sps.size + pps.size), AnnexB.nalOffsets(frame))
        assertEquals(sps.size + pps.size, AnnexB.codecConfigLength(frame, h265 = false))
        assertTrue(AnnexB.containsKeyFrame(frame, h265 = false))
    }

    @Test
    fun h264NonKeyFrameHasNoConfig() {
        val p = sc4 + bytes(0x41, 0x9A, 0x00, 0x00, 0x01, 0x23) // 载荷里的 00 00 01 23 也会被视为起始码，但类型不是参数集
        assertEquals(0, AnnexB.codecConfigLength(p, h265 = false))
        assertFalse(AnnexB.containsKeyFrame(bytes(0, 0, 0, 1, 0x41, 0x9A), h265 = false))
    }

    @Test
    fun configOnlyPacketIsEntirelyConfig() {
        val cfg = sc4 + bytes(0x67, 0x42) + sc4 + bytes(0x68, 0xCE)
        assertEquals(cfg.size, AnnexB.codecConfigLength(cfg, h265 = false))
    }

    @Test
    fun dataWithoutLeadingStartCodeIsNotConfig() {
        assertEquals(0, AnnexB.codecConfigLength(bytes(0x67, 0x42, 0, 0, 1, 0x65), h265 = false))
        assertEquals(0, AnnexB.codecConfigLength(ByteArray(0), h265 = false))
    }

    @Test
    fun h265VpsSpsPpsAreConfig() {
        val vps = sc4 + bytes(0x40, 0x01, 0x0C)
        val sps = sc4 + bytes(0x42, 0x01, 0x01)
        val pps = sc4 + bytes(0x44, 0x01, 0xC1)
        val idr = sc4 + bytes(0x26, 0x01, 0xAF) // IDR_W_RADL = 19
        val frame = vps + sps + pps + idr
        assertEquals(vps.size + sps.size + pps.size, AnnexB.codecConfigLength(frame, h265 = true))
        assertTrue(AnnexB.containsKeyFrame(frame, h265 = true))
        assertFalse(AnnexB.containsKeyFrame(sc4 + bytes(0x02, 0x01, 0xD0), h265 = true)) // TRAIL_R
    }

    // —— DMAP 元数据 ——

    private fun atom(tag: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag.toByteArray(Charsets.US_ASCII))
        val n = payload.size
        write(bytes(n ushr 24, (n ushr 16) and 0xFF, (n ushr 8) and 0xFF, n and 0xFF))
        write(payload)
    }.toByteArray()

    @Test
    fun dmapTrackInfo() {
        val items = atom("minm", "晴天".toByteArray()) + atom("asar", "周杰伦".toByteArray()) +
            atom("asal", "叶惠美".toByteArray()) + atom("astm", bytes(0, 0, 0x10, 0))
        val info = DmapMetadata.parse(atom("mlit", items))!!
        assertEquals("晴天", info.title)
        assertEquals("周杰伦", info.artist)
        assertEquals("叶惠美", info.album)
    }

    @Test
    fun dmapRejectsMalformed() {
        assertNull(DmapMetadata.parse(ByteArray(4)))
        assertNull(DmapMetadata.parse(atom("abcd", atom("minm", "x".toByteArray()))))
        // 子项声明的长度超出缓冲区：安全停止，不抛异常
        val bad = atom("mlit", bytes('m'.code, 'i'.code, 'n'.code, 'm'.code, 0x7F, 0xFF, 0xFF, 0xFF, 1, 2))
        assertNull(DmapMetadata.parse(bad))
        // 外层长度超出实际数据：按实际数据解析
        val ok = atom("mlit", atom("minm", "歌".toByteArray()))
        ok[7] = (ok[7] + 50).toByte()
        assertEquals("歌", DmapMetadata.parse(ok)?.title)
    }

    // —— 日志脱敏 ——

    @Test
    fun logRedactsUrlsAndAddresses() {
        val m = UxPlayLogRedactor.redact("on_video_play location=http://localhost:7000/master.m3u8?x=1 from 192.168.1.23 port 7000")
        assertEquals("on_video_play location=<url> from <ip> port 7000", m)
        assertEquals("mlhls <url> done", UxPlayLogRedactor.redact("mlhls mlhls://localhost/abc done"))
        assertEquals("no change", UxPlayLogRedactor.redact("no change"))
        assertEquals(
            "*** CLIENT MUST NOW ENTER PIN = <pin> AS Apple-compat PASSWORD",
            UxPlayLogRedactor.redact("*** CLIENT MUST NOW ENTER PIN = \"3738\" AS AirPlay PASSWORD"),
        )
        assertEquals("New Apple-compat connection: stopping RAOP mirror", UxPlayLogRedactor.redact("New AirPlay connection: stopping RAOP mirror"))
    }

    // —— 设备地址 / flags ——

    @Test
    fun hardwareAddressIsLocallyAdministeredUnicast() {
        val raw = bytes(0xFF, 1, 2, 3, 4, 5)
        val n = AppleDeviceAddress.normalize(raw)
        assertEquals(0x02, n[0].toInt() and 0x03)
        assertEquals(0xFE, n[0].toInt() and 0xFF)
        assertEquals(0xFF, raw[0].toInt() and 0xFF) // 不修改入参
        assertTrue(AppleDeviceAddress.isValid(n))
        assertFalse(AppleDeviceAddress.isValid(raw))
        assertFalse(AppleDeviceAddress.isValid(ByteArray(5)))
        assertFalse(AppleDeviceAddress.isValid(null))
    }

    @Test
    fun startFlags() {
        assertEquals(0, UxPlayFlags.of(requirePin = false, hls = false, h265 = false))
        assertEquals(3, UxPlayFlags.of(requirePin = true, hls = true, h265 = false))
        assertEquals(7, UxPlayFlags.of(requirePin = true, hls = true, h265 = true))
        assertEquals("listen port failed", UxPlayStartError.describe(-4))
        // 界面文案不含返回码、协议名
        for (rc in listOf(-1, -2, -3, -4, -5, -42)) {
            val msg = UxPlayStartError.userMessage(rc)
            assertFalse(msg, msg.contains(rc.toString()) || msg.contains("mDNS") || msg.contains("AirPlay"))
        }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
