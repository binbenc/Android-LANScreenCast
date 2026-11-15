package com.benchan.wireless.screencasting.protocol.dlna.dmc

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 照片推送前统一处理（PRD 8.1.1 C、技术方案 3.13.4.5）：转成 JPEG、长边 ≤ [maxLongEdge]、按 EXIF 方向转正（ImageDecoder 解码时应用）、
 * 去掉全部 EXIF（重新编码不写元数据，包括拍摄位置）。HEIC / WebP / AVIF / PNG / GIF（取第一帧）都走这里。只用系统 API。
 */
class PhotoTranscoder(private val resolver: ContentResolver, private val maxLongEdge: Int = MAX_LONG_EDGE) {
    data class Result(val file: File, val width: Int, val height: Int)

    suspend fun toJpeg(uri: Uri, out: File): Result = withContext(Dispatchers.Default) {
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, maxLongEdge.toDouble() / maxOf(w, h).coerceAtLeast(1))
            if (scale < 1.0) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        // 透明区域在 JPEG 里会变黑：铺白底
        val opaque = if (decoded.hasAlpha()) {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { bmp ->
                Canvas(bmp).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                decoded.recycle()
            }
        } else decoded
        try {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { os -> check(opaque.compress(Bitmap.CompressFormat.JPEG, QUALITY, os)) { "jpeg encode failed" } }
            Result(out, opaque.width, opaque.height)
        } finally {
            opaque.recycle()
        }
    }

    companion object {
        const val MAX_LONG_EDGE = 3840
        const val QUALITY = 90
    }
}
