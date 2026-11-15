package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.ui.components.tvInitialFocus
import com.benchan.wireless.screencasting.core.ui.components.tvSafeArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.ui.components.LatencyHistogram
import com.benchan.wireless.screencasting.core.ui.theme.DisplayNumber
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme

/**
 * 交互稿 10：本机投屏报告（F25、PRD 14、决策 #12）。四个核心数字用 2×2 网格；直方图按延迟色阶着色；
 * “只保存在本机”陈述常驻底部。统计口径：成功 = 60 秒内出首帧。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onBack: () -> Unit, onExport: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.summaryForRange.collectAsStateWithLifecycle()
    val range30 by viewModel.range30.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val ext = WscTheme.extended
    Column(Modifier.fillMaxSize().navigationBarsPadding().tvSafeArea()) {
        SettingsTopBar("投屏报告", onBack, actions = { IconButton(onClick = onExport, modifier = Modifier.tvFocusRing()) { Icon(Icons.Rounded.IosShare, "导出日志") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(range30, { viewModel.setRange30(true) }, SegmentedButtonDefaults.itemShape(0, 2), Modifier.tvInitialFocus().tvFocusRing()) { Text("最近 30 天") }
                SegmentedButton(!range30, { viewModel.setRange30(false) }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.tvFocusRing()) { Text("全部") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("投屏成功率", s.successRatePercent?.toString() ?: "—", "%", "${s.succeeded} / ${s.total} 次", null, Modifier.weight(1f))
                Metric("断线次数", s.disconnects.toString(), "次", "其中 ${s.autoRecovered} 次自动恢复", null, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val avgGrade = s.avgLatencyMs?.let(LatencyGrade::of)
                val p95Grade = s.p95LatencyMs?.let(LatencyGrade::of)
                Metric("平均延迟", s.avgLatencyMs?.toString() ?: "—", "ms", avgGrade?.label ?: "", avgGrade?.let(ext::latency), Modifier.weight(1f))
                Metric("P95 延迟", s.p95LatencyMs?.toString() ?: "—", "ms", p95Grade?.label ?: "", p95Grade?.let(ext::latency), Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("延迟分布 · 单位 ms", style = MaterialTheme.typography.titleSmall)
                LatencyHistogram(s.histogram, Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "100", "150", "250", "320").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            s.gameLatencyP50Ms?.let { p50 ->
                // F18（交互稿 G06 报告行）：游戏模式延迟与其他档位分开统计；数值 onSurface，色阶只用在圆点和等级字（UX 走查 P2-13）
                val grade = LatencyGrade.of(p50)
                val range = (if (range30) "最近 30 天" else "全部") + " · 共 " + ReportCopy.duration(s.gameDurationMs)
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "游戏模式延迟中位数约 $p50 毫秒，估算，${grade.label}，$range" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.SportsEsports, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("游戏模式延迟（中位数）", style = MaterialTheme.typography.bodyLarge)
                        Text(range, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("约 $p50\u202Fms", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).background(ext.latency(grade), androidx.compose.foundation.shape.CircleShape))
                            Text(grade.label, style = MaterialTheme.typography.labelMedium, color = ext.latency(grade))
                        }
                    }
                }
            }
            if (s.byProtocol.isNotEmpty()) {
                // 协议统计（交互稿 14-H：加“DLNA 推送”一类；推送不显示延迟）
                Text("协议", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                s.byProtocol.entries.sortedByDescending { it.value }.forEach { (p, n) ->
                    val label = when (p) {
                        com.benchan.wireless.screencasting.core.data.report.CastProtocol.WSC -> "屏幕镜像"
                        com.benchan.wireless.screencasting.core.data.report.CastProtocol.DLNA -> "DLNA 接收"
                        com.benchan.wireless.screencasting.core.data.report.CastProtocol.AIRPLAY -> "兼容 Apple 设备（非官方）"
                        com.benchan.wireless.screencasting.core.data.report.CastProtocol.DLNA_PUSH -> "DLNA 推送"
                    }
                    val extra = if (p == com.benchan.wireless.screencasting.core.data.report.CastProtocol.DLNA_PUSH) {
                        listOfNotNull(
                            s.pushSuccessRatePercent?.let { "成功率 $it%" },
                            s.pushFirstPlayP50Ms?.let { "首次播放 P50 ${"%.1f".format(it / 1000.0)} 秒" },
                        ).joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }
                    } else ""
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text("$n 次$extra", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.topDevices.isNotEmpty()) {
                Text("常用设备", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                s.topDevices.forEach { d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${d.count} 次 · ${d.avgLatencyMs?.let { "$it ms" } ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.recentControl.isNotEmpty()) {
                // 反向控制记录（F23，用户决策 D8）：只记录被谁控制、多长时间，不记坐标与内容
                Text("反向控制", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val dateFmt = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA) }
                s.recentControl.forEach { u ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本次被 ${u.peerName} 控制 ${u.durationLabel}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(dateFmt.format(java.util.Date(u.endedAt)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text("数据只保存在本机，不会上传", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.tvFocusRing()) { Text("清除记录") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部投屏记录？") },
            text = { Text("清除后无法恢复。诊断日志不受影响。") },
            confirmButton = { TextButton(onClick = { viewModel.clearReports(); confirmClear = false }, modifier = Modifier.tvFocusRing()) { Text("清除") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.tvInitialFocus().tvFocusRing()) { Text("取消") } },
        )
    }
}

@Composable
private fun Metric(title: String, value: String, unit: String, sub: String, subColor: Color?, modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title $value $unit，$sub" },
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = DisplayNumber)
            Text(" $unit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 报告页文案（纯函数）。 */
internal object ReportCopy {
    /** “3 小时”/“25 分钟”/“不到 1 分钟”。 */
    fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min >= 60 -> if (min % 60 == 0L || min >= 600) "${min / 60} 小时" else "${min / 60} 小时 ${min % 60} 分钟"
            min >= 1 -> "$min 分钟"
            else -> "不到 1 分钟"
        }
    }
}
