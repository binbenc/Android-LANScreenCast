package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme

/** 最近 60 秒延迟折线，虚线 = 100 ms（交互稿 13）。 */
@Composable
fun LatencySparkline(values: List<Int>, modifier: Modifier = Modifier, maxMs: Int = 300) {
    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outline
    Canvas(modifier.semantics { contentDescription = "最近 ${values.size} 秒延迟，最新 ${values.lastOrNull() ?: 0} 毫秒" }) {
        val y100 = size.height * (1 - 100f / maxMs)
        drawLine(guide, Offset(0f, y100), Offset(size.width, y100), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        if (values.size < 2) return@Canvas
        val step = size.width / 59f
        val start = 60 - values.size
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = (start + i) * step
            val y = size.height * (1 - v.coerceAtMost(maxMs).toFloat() / maxMs)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 3f))
    }
}

/** 延迟分布直方图，按延迟色阶着色（交互稿 10）。[histogram] 第 i 个桶 = [i*10, i*10+10) ms。 */
@Composable
fun LatencyHistogram(histogram: List<Int>, modifier: Modifier = Modifier, maxMs: Int = 320) {
    val ext = WscTheme.extended
    Canvas(modifier.semantics { contentDescription = "延迟分布图" }) {
        val buckets = (maxMs / 10).coerceAtMost(histogram.size)
        val peak = histogram.take(buckets).maxOrNull()?.coerceAtLeast(1) ?: 1
        val w = size.width / buckets
        for (i in 0 until buckets) {
            val c = histogram[i] + if (i == buckets - 1) histogram.drop(buckets).sum() else 0
            if (c == 0) continue
            val h = size.height * c / peak.toFloat()
            drawRect(ext.latency(LatencyGrade.of(i * 10 + 5)), Offset(i * w + 1, size.height - h), Size(w - 2, h))
        }
    }
}
