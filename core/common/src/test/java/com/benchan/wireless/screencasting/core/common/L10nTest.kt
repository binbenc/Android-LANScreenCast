package com.benchan.wireless.screencasting.core.common

import com.benchan.wireless.screencasting.core.common.l10n.L10n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class L10nTest {
    @Test
    fun anyChineseRegionIsChinese() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.forLanguageTag("zh-Hant-HK"), Locale.forLanguageTag("zh"))
            .forEach { assertTrue(it.toLanguageTag(), L10n.isChinese(it)) }
    }

    @Test
    fun everythingElseIsEnglish() {
        listOf(Locale.US, Locale.JAPAN, Locale.GERMANY, Locale.ROOT, Locale.forLanguageTag("yue-HK"))
            .forEach { assertFalse(it.toLanguageTag(), L10n.isChinese(it)) }
        assertEquals("en", L10n.text("en", "zh", Locale.FRANCE))
        assertEquals("zh", L10n.text("en", "zh", Locale.CHINA))
    }
}
