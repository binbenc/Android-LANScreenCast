package com.benchan.wireless.screencasting.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PhaseCTest {

    // —— 捏合：接收端跟踪 ——

    @Test
    fun pinchTrackerProducesZoomPlanInContentWidthUnits() {
        var now = 0L
        val t = PinchTracker({ now })
        // 竖屏帧 1080x2400：两指水平相距 0.2 宽 → 0.6 宽
        t.start(PointD(0.4, 0.5), PointD(0.6, 0.5), 1080, 2400)
        now = 300
        t.update(PointD(0.2, 0.5), PointD(0.8, 0.5))
        val p = t.end()!!
        assertEquals(0.2, p.from, 1e-9)
        assertEquals(0.6, p.to, 1e-9)
        assertEquals(0.5, p.x, 1e-9)
        assertEquals(0.0, p.ang, 1e-9)
        assertEquals(300L, p.dur)
        assertEquals(3.0, p.zoomFactor(), 1e-9)
        // 时长上限 400 ms
        t.start(PointD(0.4, 0.5), PointD(0.6, 0.5), 1080, 2400)
        now = 2_000
        t.update(PointD(0.3, 0.5), PointD(0.7, 0.5))
        assertEquals(PinchTracker.MAX_DUR, t.end()!!.dur)
        assertFalse(t.active)
    }

    @Test
    fun pinchTrackerUsesAspectForVerticalSpans() {
        var now = 0L
        val t = PinchTracker({ now })
        // 竖直方向 0.1 高 = 240 px = 0.2222 宽
        t.start(PointD(0.5, 0.45), PointD(0.5, 0.55), 1080, 2400)
        now = 50
        t.update(PointD(0.5, 0.4), PointD(0.5, 0.6))
        val p = t.end()!!
        assertEquals(240.0 / 1080, p.from, 1e-9)
        assertEquals(480.0 / 1080, p.to, 1e-9)
        assertEquals(Math.PI / 2, p.ang, 1e-9)
        assertEquals(PinchTracker.MIN_DUR, p.dur) // 时长下限 200 ms
    }

    @Test
    fun twoFingerTapWithoutSpanChangeIsIgnored() {
        var now = 0L
        val t = PinchTracker({ now })
        t.start(PointD(0.4, 0.5), PointD(0.6, 0.5), 1080, 2400)
        now = 120
        t.update(PointD(0.41, 0.52), PointD(0.61, 0.52)) // 平移，间距不变
        assertNull(t.end())
    }

    // —— Ctrl + 滚轮 ——

    @Test
    fun ctrlWheelUpZoomsInAndMergesNotches() {
        var now = 0L
        val w = PinchWheelCoalescer({ now })
        w.scroll(0.3, 0.7, -1.0) // 滚轮向上
        now = 20
        w.scroll(0.3, 0.7, -1.0)
        now = 40
        assertNull(w.tick())
        now = 50
        val p = w.tick()!!
        assertEquals(1.5625, p.zoomFactor(), 1e-9) // 1.25^2
        assertEquals(0.3, p.x, 0.0)
        assertNull(w.tick())
        w.scroll(0.5, 0.5, 1.0) // 向下 = 缩小
        now = 200
        assertTrue(w.tick()!!.zoomFactor() < 1.0)
    }

    // —— 捏合：发送端合成 ——

    @Test
    fun pinchSynthesizedAsTwoSymmetricStrokesInOneGesture() {
        val m = PinchSynthesizer.synthesize(540.0, 1200.0, 216.0, 648.0, 0.0, 300, 1080, 2400)!!
        assertEquals(2, m.strokes.size)
        assertEquals(300L, m.durationMs)
        val (a, b) = m.strokes
        // 起点间距 216、终点间距 648，关于中点对称
        assertEquals(216.0, b.first().x - a.first().x, 1e-6)
        assertEquals(648.0, b.last().x - a.last().x, 1e-6)
        assertEquals(540.0, (a.last().x + b.last().x) / 2, 1e-6)
        assertTrue(a.all { it.y == 1200.0 } && b.all { it.y == 1200.0 })
    }

    @Test
    fun pinchKeptInsideSafeZoneByShiftingAndScaling() {
        // 中点贴边、间距超过屏宽：先平移再等比缩小，四个端点都在安全区内
        val m = PinchSynthesizer.synthesize(10.0, 50.0, 400.0, 2000.0, Math.PI / 4, 400, 1080, 2400)!!
        val pts = m.strokes.flatten()
        assertTrue(pts.all { it.x >= 1080 * 0.06 - 1e-6 && it.x <= 1080 * 0.94 + 1e-6 })
        assertTrue(pts.all { it.y >= 2400 * 0.12 - 1e-6 && it.y <= 2400 * 0.88 + 1e-6 })
        val (a, b) = m.strokes
        val from = hypot(b.first().x - a.first().x, b.first().y - a.first().y)
        val to = hypot(b.last().x - a.last().x, b.last().y - a.last().y)
        assertEquals(5.0, to / from, 1e-6) // 缩放比保持不变
        assertNull(PinchSynthesizer.synthesize(1.0, 1.0, 0.0, 10.0, 0.0, 100, 1080, 2400))
    }

    // —— 敏感界面拦截 ——

    @Test
    fun sensitiveAppsByPackageAndClassOnly() {
        assertTrue(SensitiveApps.isSensitive("com.google.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity"))
        assertTrue(SensitiveApps.isSensitive("com.miui.packageinstaller", null))
        assertTrue(SensitiveApps.isSensitive("com.android.permissioncontroller", "x"))
        assertTrue(SensitiveApps.isSensitive("com.samsung.accessibility", null))
        assertTrue(SensitiveApps.isSensitive("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"))
        assertTrue(SensitiveApps.isSensitive("com.android.settings", "com.android.settings.accessibility.AccessibilitySettings"))
        // 设置的其他页面（Wi‑Fi 等）不拦截；普通应用不拦截
        assertFalse(SensitiveApps.isSensitive("com.android.settings", "com.android.settings.Settings\$WifiSettingsActivity"))
        assertFalse(SensitiveApps.isSensitive("com.android.settings", null))
        assertFalse(SensitiveApps.isSensitive("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"))
        assertFalse(SensitiveApps.isSensitive(null, null))
    }

    // —— 投屏结束自动关闭服务 ——

    @Test
    fun autoDisableOnlyWhenEnabledAndServiceConnected() {
        assertTrue(AutoDisablePolicy.shouldDisableOnSessionEnd(autoDisableEnabled = true, serviceConnected = true, remoteControlEnabled = true))
        assertFalse(AutoDisablePolicy.shouldDisableOnSessionEnd(autoDisableEnabled = false, serviceConnected = true, remoteControlEnabled = true))
        assertFalse(AutoDisablePolicy.shouldDisableOnSessionEnd(autoDisableEnabled = true, serviceConnected = false, remoteControlEnabled = true))
        // 总开关已关：关闭开关时已经 disableSelf()，不重复
        assertFalse(AutoDisablePolicy.shouldDisableOnSessionEnd(autoDisableEnabled = true, serviceConnected = true, remoteControlEnabled = false))
    }

    @Test
    fun multiCommandShape() {
        val m = PinchSynthesizer.synthesize(500.0, 1000.0, 100.0, 50.0, 0.0, 200, 1080, 2400)
        assertNotNull(m)
        assertTrue(m!!.strokes.all { it.size == 2 && it.first().dt == 0L && it.last().dt == 200L })
    }

    // —— 敏感界面数据表与“失败向收紧” ——

    @Test
    fun sensitiveTableCoversSystemUiAuthorizationDialogs() {
        assertTrue(SensitiveApps.isSensitive("com.android.systemui", "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity"))
        assertTrue(SensitiveApps.isSensitive("com.android.systemui", "com.android.systemui.usb.UsbDebuggingActivity"))
        assertTrue(SensitiveApps.isSensitive("com.android.systemui", "com.android.systemui.wifi.WifiDebuggingActivity"))
        // 通知栏 / 音量条不是敏感界面
        assertFalse(SensitiveApps.isSensitive("com.android.systemui", "android.widget.FrameLayout"))
        // 已知缺口：从设置首页进入的无障碍页（SubSettings）识别不到
        assertFalse(SensitiveApps.isSensitive("com.android.settings", "com.android.settings.SubSettings"))
        // 表中每个包名唯一
        assertEquals(SensitiveApps.RULES.size, SensitiveApps.RULES.map { it.packageName }.toSet().size)
        assertTrue("com.miui.packageinstaller" in SensitiveApps.INSTALLERS)
    }

    @Test
    fun trackerFailsSafeTowardTightening() {
        val t = SensitiveTracker("com.benchan.wireless.screencasting")
        val imes = setOf("com.google.android.inputmethod.latin")
        assertTrue(t.onWindow("com.google.android.packageinstaller", "x", imes))
        assertTrue(t.sensitive)
        // 输入法、Toast、框架包、系统界面普通窗口、本应用窗口：不清除敏感状态
        assertFalse(t.onWindow("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow", imes))
        assertFalse(t.onWindow("com.android.chrome", "android.widget.Toast\$TN", imes))
        assertFalse(t.onWindow("android", "android.app.AlertDialog", imes))
        assertFalse(t.onWindow("com.android.systemui", "android.widget.FrameLayout", imes))
        assertFalse(t.onWindow("com.benchan.wireless.screencasting", "x", imes))
        assertFalse(t.onWindow(null, null, imes))
        assertTrue(t.sensitive)
        // 切到普通应用 / 桌面才恢复
        assertTrue(t.onWindow("com.google.android.apps.nexuslauncher", "Launcher", imes))
        assertFalse(t.sensitive)
        // 不敏感时这些来源也不改变状态
        assertFalse(t.onWindow("com.google.android.inputmethod.latin", "x", imes))
        // 系统录屏授权对话框会进入敏感状态
        assertTrue(t.onWindow("com.android.systemui", "MediaProjectionPermissionActivity", imes))
    }
}
