package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.core.data.report.PushReport
import com.benchan.wireless.screencasting.core.data.report.TranscodeReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 8.1.1 H6 / 第 1 轮 PM P1-6：导出日志带推送字段，但搜不到文件名、网址、令牌。 */
class PushExportTest {
    @Test
    fun exportHasPushFieldsButNoNamesOrUrls() {
        val r = PushReport(
            contentType = "VIDEO", source = "PICKER", mimeTypes = listOf("video/mp4"), sizeBucket = "<1GB", itemCount = 3,
            tvManufacturer = "Hisense", tvModel = "HZ55E3D", tvServer = "Linux UPnP/1.0", upnpErrorCode = 714, fallbacks = listOf("metadata_minimal"),
            transcode = TranscodeReport(offered = true, offeredAt = "BEFORE_PUSH", choice = "TRANSCODE", sourceContainer = "video/x-matroska", sourceVideoCodec = "video/hevc", result = "SUCCESS"),
        )
        val line = PushExport.line(r)
        assertTrue(line.contains("type=VIDEO"))
        assertTrue(line.contains("upnp=714"))
        assertTrue(line.contains("transcode[offered=true"))
        assertTrue(line.contains("fallbacks=metadata_minimal"))
        listOf("家庭旅行", ".mp4", "http://", "https://", "content://").forEach { assertFalse(it, line.contains(it)) }
    }
}
