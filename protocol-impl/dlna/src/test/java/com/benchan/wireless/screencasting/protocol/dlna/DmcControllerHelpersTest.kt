package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.SpaceCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmcControllerHelpersTest {
    /** 第 1 轮 EMU-3：照片选择器给的数字名换成“视频 n / 照片 n”；正常文件名保留。 */
    @Test
    fun pickerIdNamesBecomeFriendly() {
        assertEquals("视频 1", DmcController.displayTitle("1000000020.mp4", "video/mp4", 0))
        assertEquals("照片 3", DmcController.displayTitle("1000000033.jpg", "image/jpeg", 2))
        assertEquals("音乐 2", DmcController.displayTitle(null, "audio/mpeg", 1))
        assertEquals("song1.mp3", DmcController.displayTitle("song1.mp3", "audio/mpeg", 0))
        assertEquals("2024旅行.mp4", DmcController.displayTitle("2024旅行.mp4", "video/mp4", 0))
    }

    /** 第 1 轮 PM P2-10：显示的所需空间按 PRD 公式，余量只用于判断。 */
    @Test
    fun spaceCheckReserveOnlyForDecision() {
        val c = SpaceCheck(requiredBytes = 1_000, availableBytes = 1_100, reserveBytes = 200)
        assertEquals(1_000L, c.requiredBytes)
        assertFalse(c.enough)
        assertTrue(SpaceCheck(1_000, 1_300, 200).enough)
    }

    @Test
    fun sizeBuckets() {
        assertEquals("<100MB", DmcController.sizeBucket(10L * 1024 * 1024))
        assertEquals(">=4GB", DmcController.sizeBucket(5L * 1024 * 1024 * 1024))
    }
}
