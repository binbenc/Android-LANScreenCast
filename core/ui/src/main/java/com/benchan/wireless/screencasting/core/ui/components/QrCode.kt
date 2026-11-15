package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.core.ui.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码的固定配色：白底深码，刻意不走主题 token、不随深浅色反相——反相二维码很多扫码器识别不了（交互稿 11：保证识别率）。
 */
private val QrBackground = Color.White
private val QrModule = Color(0xFF111418)

/** 二维码：固定白底深码，不随主题反相（交互稿 11：保证识别率）。 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, description: String = stringResource(R.string.core_ui_qr_default_desc)) {
    val matrix = remember(content) {
        runCatching {
            val m = QRCodeWriter().encode(
                content, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
            Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
        }.getOrNull()
    }
    Box(
        modifier.aspectRatio(1f).background(QrBackground, RoundedCornerShape(12.dp)).padding(12.dp)
            .semantics { contentDescription = description },
    ) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.size
                val cell = size.minDimension / n
                for (y in 0 until n) for (x in 0 until n) {
                    if (matrix[y][x]) drawRect(QrModule, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
