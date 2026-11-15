package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import java.util.Locale

/** “7.6 Mbps”：数值与单位间加窄空格（交互稿 05）。 */
fun formatMbps(kbps: Int): String = String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)

fun formatMs(ms: Int): String = "$ms ms"

/**
 * 状态胶囊（交互稿 03 / 07）：高 32dp、内边距 12dp，分段以 1dp outlineVariant 分隔；
 * 延迟同时显示数值、色点与等级文字（三重编码，WCAG 1.4.1，色弱可用）。
 */
@Composable
fun StatusCapsule(
    latencyMs: Int?,
    grade: LatencyGrade?,
    bitrateKbps: Int?,
    modifier: Modifier = Modifier,
    resolutionLabel: String? = null,
    noAudio: Boolean = false,
    reconnectingLabel: String? = null,
    /** 反向控制中（交互稿 R04）：追加一段“被控制中”（primary 色 + 手势图标），不挤掉延迟数值。 */
    controlled: Boolean = false,
) {
    val ext = WscTheme.extended
    val colors = MaterialTheme.colorScheme
    val desc = buildString {
        if (reconnectingLabel != null) append(reconnectingLabel) else {
            append("延迟 ").append(latencyMs?.let(::formatMs) ?: "未知")
            grade?.let { append("，").append(it.label) }
            bitrateKbps?.let { append("，码率 ").append(formatMbps(it)) }
            if (noAudio) append("，无声音")
            if (controlled) append("，被控制中")
        }
    }
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (reconnectingLabel != null) {
            Dot(ext.warning)
            Text(reconnectingLabel, style = MaterialTheme.typography.labelLarge, color = ext.warning)
            return@Row
        }
        val c = ext.latency(grade)
        Dot(c)
        Text(latencyMs?.let(::formatMs) ?: "— ms", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = colors.onSurface)
        grade?.let { Text(it.label, style = MaterialTheme.typography.labelLarge, color = c) }
        if (bitrateKbps != null) {
            Divider()
            Text(formatMbps(bitrateKbps), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
        }
        if (resolutionLabel != null) {
            Divider()
            Text(resolutionLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        if (noAudio) {
            Divider()
            Icon(Icons.Rounded.VolumeOff, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Text("无声音", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        if (controlled) {
            Divider()
            Icon(Icons.Rounded.TouchApp, null, Modifier.size(16.dp), tint = colors.primary)
            Text("被控制中", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
