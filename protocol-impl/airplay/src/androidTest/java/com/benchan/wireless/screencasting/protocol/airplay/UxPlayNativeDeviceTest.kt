package com.benchan.wireless.screencasting.protocol.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备上运行（./gradlew -Pwsc.airplay=true :protocol:airplay:connectedDebugAndroidTest）：
 * 确认 libwscairplay.so 能加载、JNI_OnLoad 的方法查找 / RegisterNatives 全部成功、FFmpeg ALAC 解码器可用。
 * 默认构建不含原生库，此时跳过。
 */
@RunWith(AndroidJUnit4::class)
class UxPlayNativeDeviceTest {
    @Test
    fun nativeLibraryLoadsAndAlacDecoderIsAvailable() {
        assumeTrue("built without -Pwsc.airplay=true", UxPlayNative.load())
        assertTrue(UxPlayNative.nativeSelfTest())
    }
}
