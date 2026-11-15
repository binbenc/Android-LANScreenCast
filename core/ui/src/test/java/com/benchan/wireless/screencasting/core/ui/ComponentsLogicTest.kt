package com.benchan.wireless.screencasting.core.ui

import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.ui.components.PinCellsDefaults
import com.benchan.wireless.screencasting.core.ui.components.latencyGradeCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsLogicTest {

    @Test
    fun castCodeCellsFitA360dpPhone() {
        // 360dp 屏宽 - 24dp × 2 页边距 = 312dp：8 位投屏码按交互稿尺寸不需要缩放
        val total = PinCellsDefaults.totalWidth(PinCellsDefaults.CastCode, 8, listOf(3, 5))
        assertTrue("total=$total", total <= 312.dp)
        assertEquals(1f, PinCellsDefaults.scaleFor(312.dp, PinCellsDefaults.CastCode, 8, listOf(3, 5)))
        // 更窄时等比缩小，且缩放后刚好放得下
        val scale = PinCellsDefaults.scaleFor(240.dp, PinCellsDefaults.CastCode, 8, listOf(3, 5))
        assertTrue(scale < 1f)
        assertTrue(total * scale <= 240.dp + 0.5.dp)
    }

    @Test
    fun pinCellsUseDesignMetrics() {
        // 4 位 PIN：56×4 + 12×3 = 260dp
        assertEquals(260.dp, PinCellsDefaults.totalWidth(PinCellsDefaults.Pin, 4, listOf(4)))
    }

    @Test
    fun histogramDescriptionHasData() {
        val h = IntArray(32).also { it[5] = 3; it[12] = 2; it[20] = 1; it[31] = 4 }.toList()
        // EXCELLENT / GOOD / FAIR / POOR
        assertEquals(listOf(3, 2, 1, 4), latencyGradeCounts(h)!!.toList())
        assertNull(latencyGradeCounts(List(32) { 0 }))
    }
}
