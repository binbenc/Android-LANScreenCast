package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.feature.settings.apkshare.ApkInfo
import com.benchan.wireless.screencasting.feature.settings.apkshare.ApkShareServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkShareTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var n = 0
    private fun fakeApk(vararg abis: String) = tmp.newFile("app${n++}.apk").also { f ->
        ZipOutputStream(FileOutputStream(f)).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(100)); z.closeEntry()
            abis.forEach { a -> z.putNextEntry(ZipEntry("lib/$a/libjingle_peerconnection_so.so")); z.write(ByteArray(10)); z.closeEntry() }
        }
    }

    @Test
    fun detectsAbis() {
        assertEquals(setOf("arm64-v8a"), ApkInfo.inspect(fakeApk("arm64-v8a")).abis)
        assertFalse(ApkInfo.inspect(fakeApk("arm64-v8a")).supports32Bit)
        assertTrue(ApkInfo.inspect(fakeApk("arm64-v8a", "armeabi-v7a")).supports32Bit)
        val t = ApkShareServer.randomToken()
        assertEquals(4, t.length)
        assertFalse(t.any { it in "0o1il" })
    }

    @Test
    fun servesPageAndApkOnlyWithToken() = runBlocking {
        val f = fakeApk("armeabi-v7a")
        val server = ApkShareServer(ApkInfo.inspect(f), "局域投屏", preferredPort = 0)
        val port = server.start()
        try {
            val base = "http://127.0.0.1:$port"
            assertEquals(404, (URL("$base/").openConnection() as HttpURLConnection).responseCode)
            assertEquals(404, (URL("$base/zzzz/${ApkShareServer.FILE_NAME}").openConnection() as HttpURLConnection).responseCode)
            val page = URL("$base/${server.token}").readText()
            assertTrue(page.contains("${server.token}/${ApkShareServer.FILE_NAME}"))
            assertFalse("no external resources", page.contains("https://"))
            val conn = URL("$base/${server.token}/${ApkShareServer.FILE_NAME}").openConnection() as HttpURLConnection
            assertEquals("application/vnd.android.package-archive", conn.contentType)
            assertArrayEquals(f.readBytes(), conn.inputStream.readBytes())
            assertEquals(1, server.downloadCount)
        } finally {
            server.stop()
        }
    }
}
