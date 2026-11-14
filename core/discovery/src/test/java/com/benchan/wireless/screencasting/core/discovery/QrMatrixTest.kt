package com.benchan.wireless.screencasting.core.discovery

import com.benchan.wireless.screencasting.core.discovery.qr.QrDecoder
import com.benchan.wireless.screencasting.core.discovery.qr.QrMatrix
import com.benchan.wireless.screencasting.core.protocol.pairing.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class QrMatrixTest {
    /** 生成 → 渲染为灰度图 → 解码，内容一致。 */
    @Test
    fun encodeThenDecode() {
        val uri = QrPayload("192.168.1.128", 8443, ByteArray(32) { it.toByte() }, ByteArray(16) { 9 }, name = "客厅电视").toUri()
        val m = QrMatrix.encode(uri)
        val scale = 4
        val quiet = 4
        val w = (m.size + quiet * 2) * scale
        val y = ByteArray(w * w) { 0xFF.toByte() }
        for (r in 0 until m.size) for (c in 0 until m.size) if (m[c, r]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                y[((r + quiet) * scale + dy) * w + (c + quiet) * scale + dx] = 0
            }
        }
        assertEquals(uri, QrDecoder.decodeY(y, w, w))
        assertEquals(QrPayload.parse(uri)!!.port, 8443)
    }
}
