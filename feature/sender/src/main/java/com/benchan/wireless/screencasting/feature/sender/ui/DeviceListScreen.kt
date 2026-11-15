@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.benchan.wireless.screencasting.feature.sender.ui

import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.discovery.DeviceProtocol
import com.benchan.wireless.screencasting.core.net.LanInfo
import com.benchan.wireless.screencasting.core.ui.components.DeviceAvatar
import com.benchan.wireless.screencasting.core.ui.components.NewDeviceBadge
import com.benchan.wireless.screencasting.core.ui.components.ProtocolTag
import com.benchan.wireless.screencasting.core.ui.components.TrustedBadge
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import kotlinx.coroutines.delay

/**
 * 交互稿 02（搜索中 · 骨架 + 3 秒兜底）/ 03（设备列表）/ 04（10 秒没找到 · 网络诊断）。
 * 骨架固定 3 行，与真实列表项同高，设备出现时原位替换，不发生布局跳动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceListScreen(
    ui: DeviceListUi,
    searchStartedAt: () -> Long,
    onDevice: (DeviceRow) -> Unit,
    onDisabledDevice: (DeviceRow) -> Unit,
    onForget: (DeviceRow) -> Unit,
    onPush: (DeviceRow) -> Unit = {},
    onDlnaDetail: (DeviceRow) -> Unit = {},
    onOpenPush: () -> Unit = {},
    onUnencryptedInfo: () -> Unit = {},
    /** 每次加 1：滚动到“电视自带投屏”分组（08B“改用推送”、空态入口）。 */
    scrollToDlna: Int = 0,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onCastCode: () -> Unit,
    onManualIp: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onInstallHelp: () -> Unit,
    /** 列表顶部的常驻卡片（F14 改用镜像横幅、F24 推荐卡 / 退出提示）。 */
    topCard: @Composable () -> Unit = {},
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    // 每次重组都重新读取：点“重新搜索”后起点会变
    val started = searchStartedAt()
    val elapsed = if (started > 0) (now - started).coerceAtLeast(0) else 0
    // 02/04（还没发现设备）：兜底卡片 / 诊断，隐藏底栏；03（已发现设备）：显示底栏，不再显示兜底卡片（U4）
    val showFallback = elapsed >= 3_000 && ui.count == 0
    val showDiagnostics = elapsed >= 10_000 && ui.count == 0
    var menuRow by remember { mutableStateOf<DeviceRow?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = { Text(stringResource(R.string.sender_title)) },
            actions = {
                // 04：没找到设备时提供“重新搜索”
                if (showDiagnostics) {
                    IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.sender_search_again)) }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.sender_settings)) }
            },
        )
        if (ui.lan == null) {
            NoLanCard(onDiagnostics)
        }
        ui.pushing?.let { PushingBanner(it, onOpenPush) }
        topCard()
        if (showDiagnostics) {
            NotFoundContent(ui.lan, elapsed, onScan, onCastCode, onDiagnostics, onInstallHelp, Modifier.weight(1f))
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(scrollToDlna) {
                if (scrollToDlna > 0 && ui.dlna.isNotEmpty()) {
                    // 头部计数 1 行 + 最近使用（标题 + 行）+ 附近设备（标题 + 行）
                    val idx = 1 + (if (ui.recent.isNotEmpty()) 1 + ui.recent.size else 0) + (if (ui.nearby.isNotEmpty()) 1 + ui.nearby.size else 0)
                    listState.animateScrollToItem(idx)
                }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                item {
                    Text(
                        if (ui.count == 0) stringResource(R.string.sender_searching_none) else pluralStringResource(R.plurals.sender_found_count, ui.count, ui.count, stringResource(if (ui.searching) R.string.sender_still_searching else R.string.sender_search_paused)),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 每行的点击：DLNA 行 = 推送（推送中的那台 = 回遥控页）；WSC 行 = 镜像，行尾“推送”按钮另算（交互稿 P01）
                val click: (DeviceRow) -> Unit = { row ->
                    when {
                        row.pushing -> onOpenPush()
                        row.dlnaOnly -> onPush(row)
                        row.enabled -> onDevice(row)
                        else -> onDisabledDevice(row)
                    }
                }
                val longClick: (DeviceRow) -> Unit = { row -> if (row.dlnaOnly) onDlnaDetail(row) else menuRow = row }
                if (ui.recent.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.sender_section_recent)) }
                    items(ui.recent, key = { "r-" + it.device.deviceId }) { row ->
                        DeviceRowItem(row, onClick = { click(row) }, onLongClick = { longClick(row) }, onPush = { onPush(row) })
                    }
                }
                if (ui.nearby.isNotEmpty() || ui.count == 0) item { SectionTitle(stringResource(R.string.sender_section_nearby)) }
                if (ui.nearby.isEmpty() && ui.count == 0) {
                    items(3) { SkeletonRow() }
                } else {
                    items(ui.nearby, key = { "n-" + it.device.deviceId }) { row ->
                        DeviceRowItem(row, onClick = { click(row) }, onLongClick = { longClick(row) }, onPush = { onPush(row) })
                    }
                }
                if (ui.dlna.isNotEmpty()) {
                    item { DlnaSectionTitle(onUnencryptedInfo) }
                    items(ui.dlna, key = { "d-" + it.device.deviceId }) { row ->
                        DeviceRowItem(row, onClick = { click(row) }, onLongClick = { longClick(row) }, onPush = { onPush(row) })
                    }
                }
                item {
                    AnimatedVisibility(showFallback, enter = fadeIn(tween(200))) {
                        FallbackCard(onScan, onCastCode, onManualIp)
                    }
                }
            }
        }
        // 兜底卡片 / 诊断页已经提供了同样的入口：隐藏底栏，避免重复（C15）
        if (showFallback || showDiagnostics) EncryptedFooter() else BottomBar(onScan, onCastCode, onManualIp)
    }

    menuRow?.let { row ->
        AlertDialog(
            onDismissRequest = { menuRow = null },
            title = { Text(row.device.name) },
            text = {
                Text(stringResource(R.string.sender_device_address, row.device.host, row.device.port) + "\n" + stringResource(if (row.trusted) R.string.sender_device_trusted else R.string.sender_device_unpaired))
            },
            confirmButton = {
                TextButton(onClick = { onForget(row); menuRow = null }, enabled = row.trusted || row.recent != null) { Text(stringResource(R.string.sender_remove_trust)) }
            },
            dismissButton = { TextButton(onClick = { menuRow = null }) { Text(stringResource(R.string.sender_close)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRowItem(row: DeviceRow, onClick: () -> Unit, onLongClick: () -> Unit, onPush: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val d = row.device
    val dlna = row.dlnaOnly
    val audioOnly = d.dlna?.audioOnly == true
    // 本应用接收端同时发现了 DLNA：整行仍是镜像，行尾另有“推送”按钮（交互稿 P01，D13-1）
    val wscPush = !dlna && d.canPush
    val subtitle = when {
        dlna && row.pushedAt != null && !row.pushing -> stringResource(R.string.sender_sub_push_last, relativeTime(row.pushedAt).asString())
        dlna -> PushCopy.dlnaSubtitle(audioOnly, row.pushing, row.disambiguation).asString()
        row.pushing -> stringResource(R.string.sender_sub_pushing)
        wscPush -> stringResource(R.string.sender_sub_mirror_push)
        row.recent != null -> stringResource(R.string.sender_sub_mirror_last, relativeTime(row.recent.lastUsedAt).asString())
        row.trusted -> stringResource(R.string.sender_sub_mirror_host, d.host)
        else -> stringResource(R.string.sender_sub_mirror_first)
    }
    val pushLabel = stringResource(R.string.sender_push)
    val clickLabel = stringResource(if (dlna) R.string.sender_push else R.string.sender_start_mirroring)
    val longClickLabel = stringResource(R.string.sender_device_details)
    val pushToDesc = stringResource(R.string.sender_push_to, d.name)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = clickLabel, onLongClickLabel = longClickLabel)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                if (wscPush) customActions = listOf(CustomAccessibilityAction(pushLabel) { onPush(); true })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceAvatar(
            when {
                dlna && audioOnly -> Icons.Rounded.MusicNote
                dlna -> Icons.Rounded.PlayCircleOutline
                else -> Icons.Rounded.Tv
            },
        )
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    // 带 [推送] 按钮的行宽度有限：单行省略，不折成“…arm6 / 4”（EMU-F5）
                    d.name, style = MaterialTheme.typography.titleMedium, maxLines = if (wscPush) 1 else 2, overflow = TextOverflow.Ellipsis,
                    color = c.onSurface, modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    dlna -> if (row.pushedAt == null) NewDeviceBadge()
                    row.trusted -> TrustedBadge()
                    else -> NewDeviceBadge()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dlna) ProtocolTag("DLNA") else Icon(Icons.Rounded.ScreenShare, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        if (wscPush) {
            // 视觉 32 dp 高、触控 48 dp（第 1 轮 UX P2-30）
            OutlinedButton(
                onClick = onPush,
                modifier = Modifier.minimumInteractiveComponentSize().height(32.dp).semantics { contentDescription = pushToDesc },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(pushLabel)
            }
        } else {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
        }
    }
}

/** “电视自带投屏 · 只能推送”分组标题 + “ⓘ 不加密”（交互稿 P01）。 */
@Composable
private fun DlnaSectionTitle(onInfo: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val infoDesc = stringResource(R.string.sender_dlna_unencrypted_a11y)
    FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Text(
            stringResource(R.string.sender_dlna_section),
            Modifier.padding(top = 12.dp, bottom = 4.dp, end = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = c.primary,
        )
        Row(
            Modifier.heightIn(min = 48.dp).clickable(onClick = onInfo).padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = infoDesc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.sender_unencrypted), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
    }
}

/** 推送中的列表顶部横幅（交互稿 P01）。 */
@Composable
private fun PushingBanner(b: PushBanner, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .background(c.primaryContainer, RoundedCornerShape(16.dp)).clickable(onClickLabel = stringResource(R.string.sender_back_to_remote), onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.PlayCircleOutline, null, tint = c.onPrimaryContainer)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sender_pushing_to, b.targetName), style = MaterialTheme.typography.titleSmall, color = c.onPrimaryContainer)
            Text(b.status.asString(), style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onPrimaryContainer)
    }
}

@Composable
private fun SkeletonRow() {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(140.dp).height(14.dp).background(c.surfaceContainerHigh, RoundedCornerShape(4.dp)))
            Box(Modifier.width(200.dp).height(10.dp).background(c.surfaceContainerHigh, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun FallbackCard(onScan: () -> Unit, onCastCode: () -> Unit, onManualIp: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(16.dp).fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.sender_fallback_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sender_fallback_body), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        Text(stringResource(R.string.sender_fallback_dlna_note), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        // FlowRow：200% 字号 / 窄屏时按钮自动换行（C11）
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sender_scan_to_connect))
            }
            FilledTonalButton(onClick = onCastCode) {
                Icon(Icons.Rounded.Pin, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sender_enter_cast_code))
            }
        }
        TextButton(onClick = onManualIp) { Text(stringResource(R.string.sender_manual_ip)) }
    }
}

@Composable
private fun NoLanCard(onDiagnostics: () -> Unit) {
    val ext = WscTheme.extended
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(ext.warningContainer, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = ext.onWarningContainer)
        Text(stringResource(R.string.sender_no_lan), Modifier.weight(1f), color = ext.onWarningContainer, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDiagnostics) { Text(stringResource(R.string.sender_diagnose)) }
    }
}

/** 交互稿 04：没找到设备 · 网络诊断（“已确认”绿勾 / “需你确认”琥珀问号；不读 SSID）。 */
@Composable
private fun NotFoundContent(
    lan: LanInfo?,
    elapsed: Long,
    onScan: () -> Unit,
    onCastCode: () -> Unit,
    onDiagnostics: () -> Unit,
    onInstallHelp: () -> Unit,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.sender_not_found_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(pluralStringResource(R.plurals.sender_not_found_body, (elapsed / 1000).toInt(), (elapsed / 1000).toInt()), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item {
            if (lan != null) {
                CheckRow(true, stringResource(R.string.sender_check_connected, stringResource(if (lan.transport == LanInfo.Transport.ETHERNET) R.string.sender_check_ethernet else R.string.sender_check_wifi)), stringResource(R.string.sender_check_connected_detail, lan.ip, lan.subnetHint))
            } else {
                CheckRow(false, stringResource(R.string.sender_check_no_wifi), stringResource(R.string.sender_check_no_wifi_detail))
            }
        }
        item { CheckRow(null, stringResource(R.string.sender_check_same_router), stringResource(R.string.sender_check_same_router_detail)) }
        item { CheckRow(null, stringResource(R.string.sender_check_ap_isolation), stringResource(R.string.sender_check_ap_isolation_detail)) }
        item { CheckRow(null, stringResource(R.string.sender_check_guest), stringResource(R.string.sender_check_guest_detail)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sender_scan_to_connect))
                }
                FilledTonalButton(onClick = onCastCode) { Text(stringResource(R.string.sender_enter_cast_code)) }
            }
        }
        item {
            Text(
                stringResource(R.string.sender_fallback_dlna_note), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }
        item { TextButton(onClick = onDiagnostics) { Text(stringResource(R.string.sender_full_diagnostics)) } }
        item {
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sender_no_receiver), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                TextButton(onClick = onInstallHelp) { Text(stringResource(R.string.sender_get_install)) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CheckRow(confirmed: Boolean?, title: String, detail: String) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val (icon, tint, label) = when (confirmed) {
        true -> Triple(Icons.Rounded.CheckCircle, ext.success, stringResource(R.string.sender_check_confirmed))
        false -> Triple(Icons.Rounded.WifiOff, c.error, stringResource(R.string.sender_check_problem))
        null -> Triple(Icons.Rounded.HelpOutline, ext.warning, stringResource(R.string.sender_check_please_confirm))
    }
    Row(
        Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label：$title。$detail" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun EncryptedFooter() {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().reportFooterHeight().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = c.tertiary)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.sender_encrypted_footer), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

@Composable
private fun BottomBar(onScan: () -> Unit, onCastCode: () -> Unit, onManualIp: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surfaceContainer).navigationBarsPadding().reportFooterHeight().padding(vertical = 8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onScan, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sender_scan))
            }
            TextButton(onClick = onCastCode, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Pin, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sender_cast_code))
            }
            TextButton(onClick = onManualIp, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("IP")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = c.tertiary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.sender_encrypted_footer), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

/** 手动输入 IP:端口（兜底路径）。 */
@Composable
internal fun ManualIpDialog(onDismiss: () -> Unit, onConnect: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sender_manual_ip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sender_manual_ip_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' }; error = false },
                    singleLine = true,
                    isError = error,
                    label = { Text(stringResource(R.string.sender_manual_ip_label)) },
                    supportingText = { if (error) Text(stringResource(R.string.sender_manual_ip_error)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (!onConnect(text)) error = true }) { Text(stringResource(R.string.sender_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sender_cancel)) } },
    )
}

internal fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): UiText {
    val d = ((now - at).coerceAtLeast(0) / 1000).toInt()
    return when {
        d < 60 -> uiText(R.string.sender_time_just_now)
        d < 3600 -> uiPlural(R.plurals.sender_time_minutes_ago, d / 60)
        d < 86_400 -> uiPlural(R.plurals.sender_time_hours_ago, d / 3600)
        else -> uiPlural(R.plurals.sender_time_days_ago, d / 86_400)
    }
}
