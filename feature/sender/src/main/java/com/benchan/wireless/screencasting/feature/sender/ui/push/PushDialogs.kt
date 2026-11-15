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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.asString

/** P02 首次推送确认卡（每台设备一次）。 */
@Composable
internal fun PushConfirmDialog(target: PushTarget, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val d = target.description
    val model = listOfNotNull(d.manufacturer, d.modelName).joinToString(" · ")
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    val ipLabel = stringResource(R.string.push_a11y_ip, target.host)
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.PlayCircleOutline, null, tint = c.secondary) },
        // 默认焦点在标题（不在确认按钮，第 1 轮 UX P2-20）
        title = { Text(stringResource(R.string.push_confirm_title), Modifier.focusRequester(titleFocus).focusable().semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.push_confirm_body), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                Column(
                    Modifier.fillMaxWidth().background(c.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp)
                        .clearAndSetSemantics { contentDescription = listOfNotNull(target.name, model.ifBlank { null }, ipLabel).joinToString(", ") },
                ) {
                    Text(target.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    if (model.isNotBlank()) Text(model, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    Text(target.host, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = c.onSurface)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                    Text(
                        stringResource(if (d.isOwnApp) R.string.push_confirm_unencrypted_own else R.string.push_confirm_unencrypted),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.push_confirm_ok)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.push_cancel)) } },
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
                stringResource(if (replacing) R.string.push_content_title_replace else R.string.push_content_title, targetName),
                Modifier.padding(horizontal = 24.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(
                    when {
                        replacing -> R.string.push_content_sub_replace
                        ownApp -> R.string.push_content_sub_own
                        else -> R.string.push_content_sub_tv
                    },
                ),
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            if (!audioOnly) SheetRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.push_content_media), stringResource(R.string.push_content_media_desc), onPicker)
            SheetRow(Icons.Rounded.Folder, stringResource(R.string.push_content_files), stringResource(if (audioOnly) R.string.push_content_files_desc_audio else R.string.push_content_files_desc), onFiles)
            SheetRow(Icons.Rounded.Link, stringResource(R.string.push_content_url), stringResource(if (audioOnly) R.string.push_content_url_desc_audio else R.string.push_content_url_desc), onUrl)
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth()
                    .background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text(stringResource(R.string.push_content_privacy), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
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
                    Text(stringResource(R.string.push_tc_phone_unsupported_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.push_tc_phone_unsupported_detail), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Button(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_try_original)) }
                    FilledTonalButton(onClick = onChangeFile, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_change_file)) }
                }
                TranscodeUi.Phase.NO_SPACE -> {
                    Text(stringResource(R.string.push_tc_no_space_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.push_tc_no_space_detail, PushCopy.size(t.requiredBytes) ?: "—", PushCopy.size(t.availableBytes) ?: "—"),
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    )
                    Button(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_try_original)) }
                    FilledTonalButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_cancel)) }
                }
                else -> {
                    Text(stringResource(R.string.push_fail_needs_transcode_title, tvName), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (t.videoOnlyAvailable) {
                        Text(
                            stringResource(R.string.push_tc_audio_unsupported, PushCopy.codecName(t.audioCodec)?.let { stringResource(R.string.push_paren, it.asString()) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                        )
                    } else {
                        Text(PushCopy.transcodeReason(t, tvName, title).asString(), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        Column(Modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp)) {
                            Text(stringResource(R.string.push_action_transcode), style = MaterialTheme.typography.titleSmall)
                            val costParts = listOfNotNull(
                                PushCopy.minutes(t.estimatedDurationMs)?.asString(),
                                PushCopy.size(t.requiredBytes)?.let { stringResource(R.string.push_tc_space_cost, it) },
                            )
                            val cost = if (costParts.size == 2) stringResource(R.string.push_join_comma, costParts[0], costParts[1]) else costParts.firstOrNull()
                            val max = stringResource(if (t.hdr) R.string.push_tc_max_1080_hdr else R.string.push_tc_max_1080)
                            Text(
                                if (cost != null) stringResource(R.string.push_tc_body_cost, cost, max) else stringResource(R.string.push_tc_body, max),
                                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.push_tc_battery_note), style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                        }
                    }
                    if (t.batteryLow) {
                        Text(
                            t.batteryPercent?.let { stringResource(R.string.push_tc_battery_low, it) } ?: stringResource(R.string.push_tc_battery_low_unknown, 20),
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
                            Text(stringResource(R.string.push_tc_auto_list), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (t.videoOnlyAvailable) {
                        Button(onClick = { onTranscode(true, autoForList) }, Modifier.fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.push_tc_video_only))
                                Text(stringResource(R.string.push_tc_video_only_note), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        FilledTonalButton(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_try_original)) }
                        TextButton(onClick = onChangeFile, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_change_file)) }
                    } else {
                        Button(onClick = { onTranscode(false, autoForList) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_transcode)) }
                        FilledTonalButton(onClick = onTryOriginal, Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_action_try_original)) }
                        Text(
                            stringResource(R.string.push_tc_original_hint), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
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
            Text(stringResource(R.string.push_playlist_title), Modifier.padding(horizontal = 24.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text(
                pluralStringResource(R.plurals.push_playlist_sub, items.size, items.size), Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = listState) {
                itemsIndexed(items, key = { _, it -> it.index }) { i, item ->
                    val isCurrent = i == current
                    val kindLabel = stringResource(PushCopy.kindLabelRes(item.kind))
                    val sub = when {
                        transcodingIndex == i -> stringResource(R.string.push_playlist_transcoding, transcodeProgress)
                        item.failed -> stringResource(R.string.push_playlist_failed)
                        isCurrent -> stringResource(R.string.push_playlist_playing)
                        else -> kindLabel
                    }
                    val itemA11y = stringResource(R.string.push_a11y_playlist_item, i + 1, items.size, item.title, kindLabel, sub)
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
                                contentDescription = itemA11y
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
                            OutlinedButton(onClick = { onTranscode(i) }) { Text(stringResource(R.string.push_transcode)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                FilledTonalButton(onClick = onChangeContent) { Text(stringResource(R.string.push_change_content)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.push_close)) }
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
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.push_cancel)) } },
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
                    Text(stringResource(R.string.push_detail_ip) + " ", color = c.onSurfaceVariant)
                    Text(target.host, fontFamily = FontFamily.Monospace, color = c.onSurface)
                }
                Text(
                    stringResource(if (d.isOwnApp) R.string.push_detail_own else R.string.push_detail_tv),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        // 按钮纵向排列：200% 字号也不挤（第 1 轮 UX P2-23）
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!d.isOwnApp) TextButton(onClick = { onDismiss(); onInstallHelp() }) { Text(stringResource(R.string.push_detail_install)) }
                if (pushedBefore) TextButton(onClick = { onForget(); onDismiss() }) { Text(stringResource(R.string.push_detail_forget)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.push_close)) }
            }
        },
    )
}

/** P01 “ⓘ 不加密”说明。 */
@Composable
internal fun UnencryptedInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.push_unencrypted_title)) },
        text = { Text(stringResource(R.string.push_unencrypted_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.push_got_it)) } },
    )
}

