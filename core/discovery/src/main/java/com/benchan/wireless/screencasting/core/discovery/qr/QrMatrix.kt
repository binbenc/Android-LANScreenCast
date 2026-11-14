package com.benchan.wireless.screencasting.core.discovery.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.common.HybridBinarizer

/** 二维码模块矩阵（不含静区），由 UI 层绘制成白底深码（交互稿 11：不随主题反相）。 */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]

    companion object {
        /** 用 ZXing core 生成（Apache-2.0，纯 Java，不依赖 Play Services）。纠错等级 M，适合 3 米外的电视屏幕。 */
        fun encode(content: String): QrMatrix {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
            val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
            val n = m.width
            return QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n) })
        }
    }
}

/** 扫码解码（CameraX 的 Y 平面 → ZXing），发送端扫码页使用。 */
object QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    fun decode(source: LuminanceSource): String? = synchronized(reader) {
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    fun decodeY(y: ByteArray, width: Int, height: Int, rowStride: Int = width): String? =
        decode(PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false))
}
