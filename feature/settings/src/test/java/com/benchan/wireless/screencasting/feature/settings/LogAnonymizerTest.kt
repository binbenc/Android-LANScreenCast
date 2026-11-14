package com.benchan.wireless.screencasting.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogAnonymizerTest {
    @Test
    fun peerNamesAreReplacedConsistently() {
        val text = "peer=小张的手机 ok\nsender 小张的手机 authenticated\npeer=客厅电视\n"
        val out = LogAnonymizer.anonymize(text, listOf("客厅电视", "小张的手机", "小张的手机"))
        assertEquals("peer=设备 A ok\nsender 设备 A authenticated\npeer=设备 B\n", out)
        assertFalse(out.contains("小张"))
    }

    @Test
    fun longerNamesWinAndShortNamesOnlyMatchWholeWords() {
        // “小张”只有 2 个字：独立出现时替换，嵌在别的词里不替换
        val out = LogAnonymizer.anonymize("小张 和 小张的手机 和 小张三", listOf("小张", "小张的手机", "DLNA"))
        assertEquals("设备 A 和 设备 B 和 小张三", out)
        assertEquals("peer=设备 A model=ATV9", LogAnonymizer.anonymize("peer=TV model=ATV9", listOf("TV")))
    }

    @Test
    fun localDeviceNameBecomesThisDevice() {
        val out = LogAnonymizer.anonymize("me=客厅电视 peer=小张的手机", listOf("小张的手机", "客厅电视"), localName = "客厅电视")
        assertEquals("me=本机 peer=设备 A", out)
    }
}
