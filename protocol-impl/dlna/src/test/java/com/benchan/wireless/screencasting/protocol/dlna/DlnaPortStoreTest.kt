package com.benchan.wireless.screencasting.protocol.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class DlnaPortStoreTest {
    private fun tempFile() = Files.createTempDirectory("dlna-port").toFile().resolve("dlna_port")

    @Test
    fun emptyOrInvalidMeansRandom() {
        val f = tempFile()
        val store = DlnaPortStore(f)
        assertNull(store.read())
        f.writeText("abc")
        assertNull(store.read())
        f.writeText("0")
        assertNull(store.read())
        f.writeText("80") // 特权端口不复用
        assertNull(store.read())
        f.writeText("70000")
        assertNull(store.read())
    }

    @Test
    fun remembersLastPortAndUpdatesOnFallback() {
        val f = tempFile()
        val store = DlnaPortStore(f)
        store.write(38473)
        assertEquals(38473, DlnaPortStore(f).read())
        // 上次端口被占用、回退到随机端口后写回新值
        store.write(43119)
        assertEquals(43119, DlnaPortStore(f).read())
        // 无效值不覆盖
        store.write(0)
        assertEquals(43119, store.read())
        f.writeText(" 50000\n")
        assertEquals(50000, store.read())
    }
}
