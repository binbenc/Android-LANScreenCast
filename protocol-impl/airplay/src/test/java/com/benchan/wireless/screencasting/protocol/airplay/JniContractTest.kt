package com.benchan.wireless.screencasting.protocol.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * JNI 契约测试：bridge/uxplay_jni.c 按“方法名 + JNI 签名”查找 [UxPlayCallbacks] 的方法、用 RegisterNatives 绑定 [UxPlayNative]。
 * 两边任何一处改名或改参数，JNI_OnLoad 都会在运行时返回 JNI_ERR（库加载失败）。这里在 JVM 单测里提前发现不一致。
 * 同时核对 C 侧 flags 位、ALAC magic cookie 与 Kotlin 常量一致。
 */
class JniContractTest {
    private val bridgeDir = File("src/main/cpp/bridge")
    private val jniSource = File(bridgeDir, "uxplay_jni.c").readText()

    private fun descriptor(c: Class<*>): String = when {
        c == Void.TYPE -> "V"
        c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"
        c == java.lang.Float.TYPE -> "F"
        c == java.lang.Double.TYPE -> "D"
        c == java.lang.Short.TYPE -> "S"
        c == java.lang.Byte.TYPE -> "B"
        c.isArray -> "[" + descriptor(c.componentType!!)
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    private fun descriptor(m: Method) = m.parameterTypes.joinToString("", "(", ")") { descriptor(it) } + descriptor(m.returnType)

    @Test
    fun callbackLookupsMatchKotlinInterface() {
        val get = Regex("""GET\(\w+,\s*"(\w+)",\s*"([^"]+)"\)""")
        val fromC = get.findAll(jniSource).associate { it.groupValues[1] to it.groupValues[2] }
        val fromKotlin = UxPlayCallbacks::class.java.declaredMethods.associate { it.name to descriptor(it) }
        assertTrue("no GET(...) lookups parsed from uxplay_jni.c", fromC.isNotEmpty())
        assertEquals(fromKotlin.toSortedMap(), fromC.toSortedMap())
    }

    @Test
    fun registeredNativesMatchKotlinFacade() {
        val callbacksClass = Regex("""#define CALLBACKS_CLASS "([^"]+)"""").find(jniSource)!!.groupValues[1]
        val nativeClass = Regex("""#define NATIVE_CLASS "([^"]+)"""").find(jniSource)!!.groupValues[1]
        assertEquals(UxPlayCallbacks::class.java.name.replace('.', '/'), callbacksClass)
        assertEquals(UxPlayNative::class.java.name.replace('.', '/'), nativeClass)

        // {"name", "(L" CALLBACKS_CLASS ";...)I", ...}：先把宏拼接展开
        val expanded = jniSource.replace("\"(L\" CALLBACKS_CLASS \";", "\"(L$callbacksClass;")
        val reg = Regex("""\{"(native\w+)",\s*"([^"]+)",""")
        val fromC = reg.findAll(expanded).associate { it.groupValues[1] to it.groupValues[2] }
        val fromKotlin = UxPlayNative::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isNative(it.modifiers) }
            .associate { it.name to descriptor(it) }
        assertEquals(fromKotlin.toSortedMap(), fromC.toSortedMap())
    }

    @Test
    fun startFlagsMatchC() {
        assertTrue(jniSource.contains("require_pin = flags & 1"))
        assertTrue(jniSource.contains("hls = (flags >> 1) & 1"))
        assertTrue(jniSource.contains("h265 = (flags >> 2) & 1"))
        assertEquals(1, UxPlayFlags.REQUIRE_PIN)
        assertEquals(2, UxPlayFlags.HLS)
        assertEquals(4, UxPlayFlags.H265)
    }

    @Test
    fun alacMagicCookieMatchesUxPlayRenderer() {
        // UxPlay renderers/audio_renderer.c alac_caps codec_data（44100 Hz / 16 bit / 2 ch / 352 spf）
        val expected = "00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44"
        val src = File(bridgeDir, "alac_decoder.c").readText()
        val body = Regex("""wsc_alac_magic_cookie\[36] = \{(.*?)};""", RegexOption.DOT_MATCHES_ALL).find(src)!!.groupValues[1]
        val hex = Regex("""0x([0-9a-fA-F]{2})|'(.)'""").findAll(body).joinToString("") { m ->
            m.groupValues[1].ifEmpty { "%02x".format(m.groupValues[2][0].code) }.lowercase()
        }
        assertEquals(expected, hex)
    }

    @Test
    fun uxplayResetTypesMatchHeader() {
        val raop = File("src/main/cpp/third_party/UxPlay/lib/raop.h")
        if (!raop.exists()) return // 未拉取子模块时跳过（默认构建不需要）
        val enumBody = Regex("""typedef enum reset_type_e \{(.*?)}""", RegexOption.DOT_MATCHES_ALL).find(raop.readText())!!.groupValues[1]
        val names = Regex("""RESET_TYPE_\w+""").findAll(enumBody).map { it.value }.toList()
        assertEquals(
            listOf("RESET_TYPE_NOHOLD", "RESET_TYPE_RTP_SHUTDOWN", "RESET_TYPE_HLS_SHUTDOWN", "RESET_TYPE_HLS_EOS", "RESET_TYPE_ON_VIDEO_PLAY", "RESET_TYPE_RTP_TO_HLS_TEARDOWN"),
            names,
        )
    }
}
