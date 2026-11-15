package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.quality.CaptureSize
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputGeometryTest {

    @Test
    fun receiverLetterboxIsDropped() {
        // 竖屏手机画面（1080x2400）放在横屏平板视图（2560x1600）里：左右黑边
        val view = 2560 to 1600
        val frame = 1072 to 2384
        val rect = InputGeometry.videoRectInView(view.first, view.second, frame.first, frame.second)!!
        assertEquals(1600.0, rect.height, 1e-6)
        assertNull(InputGeometry.viewToNormalized(view.first, view.second, frame.first, frame.second, 10f, 800f))
        val center = InputGeometry.viewToNormalized(view.first, view.second, frame.first, frame.second, 1280f, 800f)!!
        assertEquals(0.5, center.x, 1e-6)
        assertEquals(0.5, center.y, 1e-6)
        val topLeft = InputGeometry.viewToNormalized(view.first, view.second, frame.first, frame.second, rect.left.toFloat(), 0f)!!
        assertEquals(0.0, topLeft.x, 1e-6)
        assertEquals(0.0, topLeft.y, 1e-6)
        val bottomRight = InputGeometry.viewToNormalized(view.first, view.second, frame.first, frame.second, rect.right.toFloat(), 1600f)!!
        assertEquals(1.0, bottomRight.x, 1e-6)
        assertEquals(1.0, bottomRight.y, 1e-6)
    }

    @Test
    fun senderUndoesSixteenPixelAlignment() {
        // 1080x2400 屏幕在均衡档：CaptureSize 向下对齐到 16 → 内容在帧里有不到 16 px 的黑边
        val cap = CaptureSize.fit(1080, 2400, QualityProfile.BALANCED)
        val g = CaptureGeometry(1, cap.width, cap.height, 1080, 2400)
        val rect = InputGeometry.contentRectInCapture(g)!!
        assertTrue(rect.width <= cap.width + 1e-9 && rect.height <= cap.height + 1e-9)
        // 归一化中心 → 屏幕中心
        val c = InputGeometry.normalizedToContent(g, 0.5, 0.5)!!
        assertEquals(540.0, c.x, 1.0)
        assertEquals(1200.0, c.y, 1.0)
        // 四角夹在屏幕内
        val tl = InputGeometry.normalizedToContent(g, rect.left / cap.width, rect.top / cap.height)!!
        assertEquals(0.0, tl.x, 0.5)
        assertEquals(0.0, tl.y, 0.5)
        val br = InputGeometry.normalizedToContent(g, 1.0, 1.0)
        assertNotNull(br)
        assertTrue(br!!.x <= 1079.0 && br.y <= 2399.0)
    }

    @Test
    fun captureLetterboxOutsideContentIsDropped() {
        // 人为制造明显的黑边：内容 1000x2000 放进 1200x2000 的帧，左右各 100 px
        val g = CaptureGeometry(1, 1200, 2000, 1000, 2000)
        assertNull(InputGeometry.normalizedToContent(g, 0.02, 0.5))
        val p = InputGeometry.normalizedToContent(g, 0.5, 0.25)!!
        assertEquals(500.0, p.x, 1e-6)
        assertEquals(500.0, p.y, 1e-6)
    }

    @Test
    fun webrtcDownscaleKeepsMapping() {
        // 发送端 1072x2384；WebRTC 自适应缩到 3/4（804x1788）：宽高比一致，代号仍匹配
        val g = CaptureGeometry(5, 1072, 2384, 1080, 2400)
        assertTrue(InputGeometry.aspectMatches(804, 1788, g.captureWidth, g.captureHeight))
        assertEquals(5, InputGeometry.pickGeneration(804, 1788, listOf(CaptureGeometry(4, 2384, 1072, 2400, 1080), g)))
    }

    @Test
    fun rotationPicksMatchingGenerationOrNone() {
        val portrait = CaptureGeometry(1, 1072, 2384, 1080, 2400)
        val landscape = CaptureGeometry(2, 2384, 1072, 2400, 1080)
        val list = listOf(portrait, landscape)
        assertEquals(2, InputGeometry.pickGeneration(2384, 1072, list))
        // 旋转后还在渲染旧的竖屏帧：仍然匹配旧代号（发送端会因代号过期丢弃并回 GEOM_STALE）
        assertEquals(1, InputGeometry.pickGeneration(1072, 2384, list))
        assertNull(InputGeometry.pickGeneration(1000, 1000, list))
    }

    @Test
    fun coversScreen() {
        assertTrue(InputGeometry.coversScreen(1080, 2400, 1080, 2400))
        assertTrue(InputGeometry.coversScreen(1079, 2401, 1080, 2400))
        assertFalse(InputGeometry.coversScreen(1080, 1170, 1080, 2400))
        assertFalse(InputGeometry.aspectMatches(0, 1, 1, 1))
    }

    /** TV 光标顶 / 底边、鼠标滚轮贴边与黑边上的滚动锚点：夹回内容区并合成滑动，不能被拒绝（复测问题）。 */
    @Test
    fun wheelAnchorAtEdgesAndLetterboxIsClampedNotRejected() {
        // 1080x2400 屏幕 → 均衡档采集帧（16 对齐，上下有几像素黑边）
        val cap = CaptureSize.fit(1080, 2400, QualityProfile.BALANCED)
        val g = CaptureGeometry(1, cap.width, cap.height, 1080, 2400)
        // 普通映射在 y = 1.0 时可能落在黑边外被拒（复现原问题的前提）；夹取映射一律有结果
        for (ny in listOf(0.0, 1.0)) {
            val p = InputGeometry.normalizedToContentClamped(g, 0.5, ny)
            assertNotNull(p)
            assertTrue(p!!.y in 0.0..2399.0)
            val swipe = WheelSynthesizer.synthesize(p.x, p.y, if (ny > 0.5) 1.0 / 3 else -1.0 / 3, 1080, 2400)
            assertEquals(2, swipe.size)
            val start = (swipe[0] as GestureCommand.Segment).points.first()
            assertTrue(start.y >= 2400 * 0.12 - 1e-6 && start.y <= 2400 * 0.88 + 1e-6)
        }
        // 明显的左右黑边：锚点在黑边里 → 夹到最近的内容边缘
        val wide = CaptureGeometry(2, 1200, 2000, 1000, 2000)
        assertNull(InputGeometry.normalizedToContent(wide, 0.02, 0.5))
        val left = InputGeometry.normalizedToContentClamped(wide, 0.02, 0.5)!!
        assertEquals(0.0, left.x, 1e-6)
        val right = InputGeometry.normalizedToContentClamped(wide, 0.99, 0.5)!!
        assertEquals(999.0, right.x, 1e-6)
        assertEquals(2, WheelSynthesizer.synthesize(left.x, left.y, 0.08, 1000, 2000).size)
        // 几何无效才返回 null
        assertNull(InputGeometry.normalizedToContentClamped(CaptureGeometry(3, 0, 0, 1, 1), 0.5, 0.5))
    }
}
