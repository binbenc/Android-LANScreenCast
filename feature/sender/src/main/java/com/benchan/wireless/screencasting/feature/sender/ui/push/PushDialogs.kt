package com.benchan.wireless.screencasting.feature.sender.ui.push

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.benchan.wireless.screencasting.core.ui.theme.onScrim
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushItemInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushTarget
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind

/** P02 首次推送确认卡（每台设备一次）。 */
@Composable
internal fun PushConfirmDialog(target: PushTarget, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val d = target.description
    val model = listOfNotNull(d.manufacturer, d.modelName).joinToString(" · ")
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.PlayCircleOutline, null, tint = c.secondary) },
        // 默认焦点在标题（不在确认按钮，第 1 轮 UX P2-20）
        title = { Text("是你面前的这台电视吗？", Modifier.focusRequester(titleFocus).focusable().semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("公寓、酒店里可能有同名的电视。请核对名称和型号，避免推到别人家的电视上。", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                Column(
                    Modifier.fillMaxWidth().background(c.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp)
                        .clearAndSetSemantics { contentDescription = listOfNotNull(target.name, model.ifBlank { null }, "IP ${target.host}").joinToString("，") },
                ) {
                    Text(target.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    if (model.isNotBlank()) Text(model, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    Text(target.host, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = c.onSurface)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                    Text(
                        if (d.isOwnApp) "推送不加密，同一 Wi‑Fi 里的其他设备有可能看到推送的内容。" else "电视自带投屏不加密，同一 Wi‑Fi 里的其他设备有可能看到推送的内容。",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("是这台，继续") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/** P03 选择推送内容（换内容时 [replacing] = true）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushContentSheet(
    targetName: String,
    audioOnly: Boolean,
    replacing: Boolean,
    ownApp: Boolean = false,
    onPicker: () -> Unit,
    onFiles: () -> Unit,
    onUrl: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (replacing) "换成其他内容 · $targetName" else "推送到 $targetName",
                Modifier.padding(horizontal = 24.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
            )
            Text(
                when {
                    replacing -> "当前播放会被替换"
                    ownApp -> "DLNA 本应用接收端 · 原画质，手机可以锁屏"
                    else -> "DLNA 电视自带投屏 · 原画质，手机可以锁屏"
                },
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            if (!audioOnly) SheetRow(Icons.Rounded.PhotoLibrary, "照片和视频", "从相册选，可以多选，最多 100 项", onPicker)
            SheetRow(Icons.Rounded.Folder, "文件", if (audioOnly) "音乐文件" else "视频、音乐、图片，例如下载目录、U 盘", onFiles)
            SheetRow(Icons.Rounded.Link, "网址", if (audioOnly) "音乐文件的地址" else "电视直接联网打开视频文件地址", onUrl)
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth()
                    .background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text("不需要存储权限。只有你选中的文件会分享给这台电视，推送结束后立即停止分享。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(c.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
    }
}

/** P10 转换提议（含：只能转画面 / 手机也解不了 / 空间不足 三种形态）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscodeOfferSheet(
    t: TranscodeUi,
    tvName: String,
    title: String,
    onTranscode: (videoOnly: Boolean, autoForList: Boolean) -> Unit,
    onTryOriginal: () -> Unit,
    onChangeFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var autoForList by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (t.phase) {
                TranscodeUi.Phase.PHONE_UNSUPPORTED -> {
                    Text("这台手机也无法转换这个视频", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text("手机没有这种格式的解码器", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Button(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text("直接尝试原文件") }
                    FilledTonalButton(onClick = onChangeFile, Modifier.fillMaxWidth()) { Text("换一个文件") }
                }
                TranscodeUi.Phase.NO_SPACE -> {
                    Text("手机空间不足", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "转换需要约 ${PushCopy.size(t.requiredBytes) ?: "—"}，当前可用 ${PushCopy.size(t.availableBytes) ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    )
                    Button(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text("直接尝试原文件") }
                    FilledTonalButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("取消") }
                }
                else -> {
                    Text("$tvName 可能放不了这个视频", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (t.videoOnlyAvailable) {
                        Text(
                            "视频里的声音格式" + (PushCopy.codecName(t.audioCodec)?.let { "（$it）" } ?: "") + "电视和这台手机都处理不了，只能转换画面。",
                            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                        )
                    } else {
                        Text(PushCopy.transcodeReason(t, tvName, title), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        Column(Modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp)) {
                            Text("转换后推送", style = MaterialTheme.typography.titleSmall)
                            val cost = listOfNotNull(PushCopy.minutes(t.estimatedDurationMs), PushCopy.size(t.requiredBytes)?.let { "占用约 $it 空间" }).joinToString("，")
                            Text(
                                "在手机上转成电视普遍支持的格式，转完自动推送。" + (if (cost.isNotEmpty()) "$cost。" else "") +
                                    "最高 1080p" + (if (t.hdr) "；颜色可能和原视频略有差别（HDR 转为普通画面）。" else "。"),
                                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                            )
                            Text("转换会占用较多电量并让手机发热，建议接上充电器。", style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                        }
                    }
                    if (t.batteryLow) {
                        Text(
                            "电量 ${t.batteryPercent ?: "低于 20"}%，未在充电。转换中途可能没电",
                            Modifier.fillMaxWidth().background(c.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                            color = c.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (t.inPlaylist) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = autoForList, role = Role.Checkbox, onValueChange = { autoForList = it }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = autoForList, onCheckedChange = null)
                            Text("本次列表里其他需要转换的视频也自动转换", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (t.videoOnlyAvailable) {
                        Button(onClick = { onTranscode(true, autoForList) }, Modifier.fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("只转换画面（无声音）")
                                Text("电视上会没有声音", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        FilledTonalButton(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text("直接尝试原文件") }
                        TextButton(onClick = onChangeFile, Modifier.fillMaxWidth()) { Text("换一个文件") }
                    } else {
                        Button(onClick = { onTranscode(false, autoForList) }, Modifier.fillMaxWidth()) { Text("转换后推送") }
                        FilledTonalButton(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text("直接尝试原文件") }
                        Text(
                            "原文件放不了的话，20 秒内会告诉你", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** P08 播放列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSheet(
    items: List<PushItemInfo>,
    current: Int,
    transcodingIndex: Int?,
    transcodeProgress: Int,
    onSelect: (Int) -> Unit,
    onTranscode: (Int) -> Unit,
    onChangeContent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem((current - 2).coerceAtLeast(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("播放列表", Modifier.padding(horizontal = 24.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text(
                "${items.size} 项 · 播完自动播下一个", Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = listState) {
                itemsIndexed(items, key = { _, it -> it.index }) { i, item ->
                    val isCurrent = i == current
                    val sub = when {
                        transcodingIndex == i -> "正在转换 $transcodeProgress%"
                        item.failed -> "电视无法播放 · 已跳过"
                        isCurrent -> "正在播放"
                        else -> PushCopy.kindLabel(item.kind)
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .background(if (isCurrent) c.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onSelect(i) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(72.dp, 48.dp).background(c.scrim, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            if (!item.remote && item.kind != MediaKind.AUDIO) {
                                rememberThumbnail(item.uri, 216, 144)?.let { b ->
                                    androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                                }
                            }
                            Icon(if (isCurrent) Icons.Rounded.PlayArrow else kindIcon(item.kind), null, Modifier.size(20.dp), tint = c.onScrim)
                        }
                        Column(
                            Modifier.weight(1f).semantics(mergeDescendants = true) {
                                selected = isCurrent
                                contentDescription = "第 ${i + 1} 项，共 ${items.size} 项，${item.title}，${PushCopy.kindLabel(item.kind)}，$sub"
                            },
                        ) {
                            Text(
                                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrent) c.onPrimaryContainer else c.onSurface,
                            )
                            Text(
                                sub, style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    transcodingIndex == i -> if (isCurrent) c.onPrimaryContainer else c.primary
                                    item.failed -> if (isCurrent) c.onPrimaryContainer else c.error
                                    isCurrent -> c.onPrimaryContainer
                                    else -> c.onSurfaceVariant
                                },
                            )
                        }
                        if (item.failed && item.kind == MediaKind.VIDEO && !item.remote) {
                            OutlinedButton(onClick = { onTranscode(i) }) { Text("转换") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                FilledTonalButton(onClick = onChangeContent) { Text("换内容") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

internal fun kindIcon(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.VIDEO -> Icons.Rounded.Videocam
    MediaKind.AUDIO -> Icons.Rounded.MusicNote
    MediaKind.IMAGE -> Icons.Rounded.Image
}

/** P15 互斥确认（默认焦点在“取消”）。 */
@Composable
internal fun PushMutexDialog(title: String, text: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    // 破坏性切换：默认焦点在“取消”（第 1 轮 UX P2-22）
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, Modifier.semantics { heading() }) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("取消") } },
    )
}

/** P01 长按 DLNA 行：设备详情。 */
@Composable
internal fun DlnaDeviceDetailDialog(
    target: PushTarget,
    pushedBefore: Boolean,
    onInstallHelp: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val d = target.description
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(d.manufacturer, d.modelName).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, color = c.onSurface) }
                Row {
                    Text("IP 地址 ", color = c.onSurfaceVariant)
                    Text(target.host, fontFamily = FontFamily.Monospace, color = c.onSurface)
                }
                Text(
                    if (d.isOwnApp) "这台设备已安装本应用，可以镜像，也可以推送。" else "这台电视不能镜像手机屏幕。在电视上安装本应用后就可以镜像。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        // 按钮纵向排列：200% 字号也不挤（第 1 轮 UX P2-23）
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!d.isOwnApp) TextButton(onClick = { onDismiss(); onInstallHelp() }) { Text("获取安装方法") }
                if (pushedBefore) TextButton(onClick = { onForget(); onDismiss() }) { Text("忘记这台设备") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** P01 “ⓘ 不加密”说明。 */
@Composable
internal fun UnencryptedInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电视自带投屏不加密") },
        text = { Text("电视自带投屏不支持加密。推送的照片和视频在局域网内以明文传输，在酒店、公共 Wi‑Fi 里，同一网络的其他人有可能截获。屏幕镜像不受影响，全程加密。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

