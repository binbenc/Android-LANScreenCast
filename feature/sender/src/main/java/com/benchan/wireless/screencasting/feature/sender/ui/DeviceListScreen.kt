@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.benchan.wireless.screencasting.feature.sender.ui

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
            title = { Text("投屏") },
            actions = {
                // 04：没找到设备时提供“重新搜索”
                if (showDiagnostics) {
                    IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "重新搜索") }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "设置") }
            },
        )
        if (ui.lan == null) {
            NoLanCard(onDiagnostics)
        }
        ui.pushing?.let { PushingBanner(it, onOpenPush) }
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
                        if (ui.count == 0) "正在搜索同一网段的设备 · 已发现 0" else "已发现 ${ui.count} 台 · ${if (ui.searching) "仍在搜索" else "搜索已暂停"}",
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
                    item { SectionTitle("最近使用") }
                    items(ui.recent, key = { "r-" + it.device.deviceId }) { row ->
                        DeviceRowItem(row, onClick = { click(row) }, onLongClick = { longClick(row) }, onPush = { onPush(row) })
                    }
                }
                if (ui.nearby.isNotEmpty() || ui.count == 0) item { SectionTitle("附近设备") }
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
                Text("地址 ${row.device.host}:${row.device.port}\n" + if (row.trusted) "已信任：再次连接无需输入 PIN" else "尚未配对")
            },
            confirmButton = {
                TextButton(onClick = { onForget(row); menuRow = null }, enabled = row.trusted || row.recent != null) { Text("移除信任") }
            },
            dismissButton = { TextButton(onClick = { menuRow = null }) { Text("关闭") } },
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
        dlna && row.pushedAt != null && !row.pushing -> "推送 · 上次 ${relativeTime(row.pushedAt)}"
        dlna -> PushCopy.dlnaSubtitle(audioOnly, row.pushing, row.disambiguation)
        row.pushing -> "正在推送"
        wscPush -> "镜像 · 也可推送"
        row.recent != null -> "屏幕镜像 · 上次 ${relativeTime(row.recent.lastUsedAt)}"
        row.trusted -> "屏幕镜像 · ${d.host}"
        else -> "屏幕镜像 · 首次连接需输入 PIN"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = if (dlna) "推送" else "开始镜像", onLongClickLabel = "设备详情")
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                if (wscPush) customActions = listOf(CustomAccessibilityAction("推送") { onPush(); true })
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
                    d.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                modifier = Modifier.minimumInteractiveComponentSize().height(32.dp).semantics { contentDescription = "推送到 ${d.name}" },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("推送")
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
    FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Text(
            "电视自带投屏 · 只能推送",
            Modifier.padding(top = 12.dp, bottom = 4.dp, end = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = c.primary,
        )
        Row(
            Modifier.heightIn(min = 48.dp).clickable(onClick = onInfo).padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = "电视自带投屏不加密，了解详情" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("不加密", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
    }
}

/** 推送中的列表顶部横幅（交互稿 P01）。 */
@Composable
private fun PushingBanner(b: PushBanner, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .background(c.primaryContainer, RoundedCornerShape(16.dp)).clickable(onClickLabel = "回到遥控页", onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.PlayCircleOutline, null, tint = c.onPrimaryContainer)
        Column(Modifier.weight(1f)) {
            Text("正在推送到 ${b.targetName}", style = MaterialTheme.typography.titleSmall, color = c.onPrimaryContainer)
            Text(b.status, style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer)
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
        Text("没看到你的电视？", style = MaterialTheme.typography.titleMedium)
        Text("路由器可能拦截了自动发现。可以直接连接：", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        Text("电视自带投屏需要路由器允许设备互相发现", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        // FlowRow：200% 字号 / 窄屏时按钮自动换行（C11）
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("扫码连接")
            }
            FilledTonalButton(onClick = onCastCode) {
                Icon(Icons.Rounded.Pin, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("输入投屏码")
            }
        }
        TextButton(onClick = onManualIp) { Text("手动输入 IP 地址") }
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
        Text("没有连接 Wi‑Fi。手机和电视要连同一个路由器。", Modifier.weight(1f), color = ext.onWarningContainer, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDiagnostics) { Text("诊断") }
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
                Text("没有发现设备", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("已搜索 ${elapsed / 1000} 秒。多半是网络设置导致的，可以按下面检查：", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item {
            if (lan != null) {
                CheckRow(true, "手机已连接 ${if (lan.transport == LanInfo.Transport.ETHERNET) "有线网络" else "Wi‑Fi"}", "本机地址 ${lan.ip}，电视应为 ${lan.subnetHint}")
            } else {
                CheckRow(false, "手机没有连接 Wi‑Fi", "请先连接家里的路由器")
            }
        }
        item { CheckRow(null, "电视和手机连的是同一个路由器吗？", "双频路由的 2.4G 与 5G 有时互不相通") }
        item { CheckRow(null, "路由器是否开启了“AP 隔离”？", "也叫无线隔离 / 客户端隔离") }
        item { CheckRow(null, "是否连接了“访客网络”？", "访客网络通常禁止设备互访，请换到主网络") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫码连接")
                }
                FilledTonalButton(onClick = onCastCode) { Text("输入投屏码") }
            }
        }
        item {
            Text(
                "电视自带投屏需要路由器允许设备互相发现", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }
        item { TextButton(onClick = onDiagnostics) { Text("完整网络诊断（组播检测）") } }
        item {
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                Text("电视还没装接收端？", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                TextButton(onClick = onInstallHelp) { Text("获取安装方法") }
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
        true -> Triple(Icons.Rounded.CheckCircle, ext.success, "已确认")
        false -> Triple(Icons.Rounded.WifiOff, c.error, "有问题")
        null -> Triple(Icons.Rounded.HelpOutline, ext.warning, "需你确认")
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
        Text("屏幕镜像全程加密", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

@Composable
private fun BottomBar(onScan: () -> Unit, onCastCode: () -> Unit, onManualIp: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surfaceContainer).navigationBarsPadding().reportFooterHeight().padding(vertical = 8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onScan, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("扫码")
            }
            TextButton(onClick = onCastCode, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Pin, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("投屏码")
            }
            TextButton(onClick = onManualIp, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("IP")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = c.tertiary)
            Spacer(Modifier.width(4.dp))
            Text("屏幕镜像全程加密", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
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
        title = { Text("手动输入 IP 地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("在电视待机页上可以看到地址，例如 192.168.1.128 : 8443", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' }; error = false },
                    singleLine = true,
                    isError = error,
                    label = { Text("IP 地址:端口") },
                    supportingText = { if (error) Text("地址格式不正确") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (!onConnect(text)) error = true }) { Text("连接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun relativeTime(at: Long): String {
    val d = (System.currentTimeMillis() - at).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86_400 -> "${d / 3600} 小时前"
        else -> "${d / 86_400} 天前"
    }
}
