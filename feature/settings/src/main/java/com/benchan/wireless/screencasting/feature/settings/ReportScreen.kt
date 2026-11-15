package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.core.ui.components.formatApproxMs
import com.benchan.wireless.screencasting.core.ui.text.displayLabel
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.ui.R as UiR
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
        SettingsTopBar(stringResource(R.string.settings_report), onBack, actions = { IconButton(onClick = onExport, modifier = Modifier.tvFocusRing()) { Icon(Icons.Rounded.IosShare, stringResource(R.string.settings_report_export_logs)) } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(range30, { viewModel.setRange30(true) }, SegmentedButtonDefaults.itemShape(0, 2), Modifier.tvInitialFocus().tvFocusRing()) { Text(stringResource(R.string.settings_report_range_30)) }
                SegmentedButton(!range30, { viewModel.setRange30(false) }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.tvFocusRing()) { Text(stringResource(R.string.settings_report_range_all)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(stringResource(R.string.settings_report_success_rate), s.successRatePercent?.toString() ?: "—", "%", pluralStringResource(R.plurals.settings_report_success_sub, s.total, s.succeeded, s.total), null, Modifier.weight(1f))
                Metric(stringResource(R.string.settings_report_disconnects), s.disconnects.toString(), stringResource(R.string.settings_report_unit_times), pluralStringResource(R.plurals.settings_report_recovered, s.autoRecovered, s.autoRecovered), null, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val avgGrade = s.avgLatencyMs?.let(LatencyGrade::of)
                val p95Grade = s.p95LatencyMs?.let(LatencyGrade::of)
                Metric(stringResource(R.string.settings_report_avg_latency), s.avgLatencyMs?.toString() ?: "—", "ms", avgGrade?.displayLabel() ?: "", avgGrade?.let(ext::latency), Modifier.weight(1f))
                Metric(stringResource(R.string.settings_report_p95_latency), s.p95LatencyMs?.toString() ?: "—", "ms", p95Grade?.displayLabel() ?: "", p95Grade?.let(ext::latency), Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text(stringResource(R.string.settings_report_histogram_title), style = MaterialTheme.typography.titleSmall)
                LatencyHistogram(s.histogram, Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "100", "150", "250", "320").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            s.gameLatencyP50Ms?.let { p50 ->
                // F18（交互稿 G06 报告行）：游戏模式延迟与其他档位分开统计；数值 onSurface，色阶只用在圆点和等级字（UX 走查 P2-13）
                val grade = LatencyGrade.of(p50)
                val range = stringResource(
                    R.string.settings_report_game_range,
                    stringResource(if (range30) R.string.settings_report_range_30 else R.string.settings_report_range_all),
                    ReportCopy.duration(s.gameDurationMs),
                )
                val gradeLabel = grade.displayLabel()
                val gameDesc = pluralStringResource(R.plurals.settings_report_game_desc, p50, p50, gradeLabel, range)
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)
                        .semantics(mergeDescendants = true) { contentDescription = gameDesc },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.SportsEsports, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_report_game_title), style = MaterialTheme.typography.bodyLarge)
                        Text(range, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatApproxMs(p50), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).background(ext.latency(grade), androidx.compose.foundation.shape.CircleShape))
                            Text(gradeLabel, style = MaterialTheme.typography.labelMedium, color = ext.latency(grade))
                        }
                    }
                }
            }
            if (s.byProtocol.isNotEmpty()) {
                // 协议统计（交互稿 14-H：加“DLNA 推送”一类；推送不显示延迟）
                Text(stringResource(R.string.settings_report_protocols), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                s.byProtocol.entries.sortedByDescending { it.value }.forEach { (p, n) ->
                    val label = stringResource(
                        when (p) {
                            CastProtocol.WSC -> R.string.settings_report_proto_mirroring
                            CastProtocol.DLNA -> R.string.settings_report_proto_dlna
                            CastProtocol.AIRPLAY -> R.string.settings_report_proto_apple
                            CastProtocol.DLNA_PUSH -> R.string.settings_report_proto_dlna_push
                        },
                    )
                    val extras = if (p == CastProtocol.DLNA_PUSH) {
                        listOfNotNull(
                            s.pushSuccessRatePercent?.let { stringResource(R.string.settings_report_push_success, it) },
                            s.pushFirstPlayP50Ms?.let { stringResource(R.string.settings_report_push_first_play, "%.1f".format(it / 1000.0)) },
                        )
                    } else emptyList()
                    val countText = (listOf(pluralStringResource(R.plurals.settings_report_count, n, n)) + extras).joinToString(" · ")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(countText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.topDevices.isNotEmpty()) {
                Text(stringResource(R.string.settings_report_top_devices), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                s.topDevices.forEach { d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text(pluralStringResource(R.plurals.settings_report_count, d.count, d.count) + " · " + (d.avgLatencyMs?.let { "$it ms" } ?: "—"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.recentControl.isNotEmpty()) {
                // 反向控制记录（F23，用户决策 D8）：只记录被谁控制、多长时间，不记坐标与内容
                Text(stringResource(R.string.settings_report_remote_control), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                // 按当前语言取“月日 时:分”格式（中文为“M月d日 HH:mm”）
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                val dateFmt = remember(locale) { java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdHHmm"), locale) }
                s.recentControl.forEach { u ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_report_controlled, u.peerName, u.durationLabel), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(dateFmt.format(java.util.Date(u.endedAt)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(R.string.settings_report_local_only), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.tvFocusRing()) { Text(stringResource(R.string.settings_report_clear)) }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_report_clear_title)) },
            text = { Text(stringResource(R.string.settings_report_clear_text)) },
            confirmButton = { TextButton(onClick = { viewModel.clearReports(); confirmClear = false }, modifier = Modifier.tvFocusRing()) { Text(stringResource(R.string.settings_report_clear_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.tvInitialFocus().tvFocusRing()) { Text(stringResource(UiR.string.core_ui_action_cancel)) } },
        )
    }
}

@Composable
private fun Metric(title: String, value: String, unit: String, sub: String, subColor: Color?, modifier: Modifier) {
    val desc = stringResource(R.string.settings_report_metric_desc, title, value, unit, sub)
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
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
    /** “3 h”/“1 h 5 min”/“25 min”/“less than 1 min”（中文“3 小时”/“1 小时 5 分钟”/“25 分钟”/“不到 1 分钟”）。 */
    @Composable
    fun duration(ms: Long): String {
        val min = (ms / 60_000).toInt()
        return when {
            min >= 60 -> if (min % 60 == 0 || min >= 600) stringResource(R.string.settings_duration_hours, min / 60)
            else stringResource(R.string.settings_duration_hours_minutes, min / 60, min % 60)
            min >= 1 -> stringResource(R.string.settings_duration_minutes, min)
            else -> stringResource(R.string.settings_duration_under_minute)
        }
    }
}
