@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.benchan.wireless.screencasting.feature.sender.ui

import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.ui.res.painterResource
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.feature.control.ControlCopy
import com.benchan.wireless.screencasting.feature.control.ControlUi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.ui.components.DeviceAvatar
import com.benchan.wireless.screencasting.core.ui.components.EncryptedLabel
import com.benchan.wireless.screencasting.core.ui.components.StatusCapsule
import com.benchan.wireless.screencasting.core.ui.theme.DisplayNumber
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import kotlinx.coroutines.delay

/**
 * 交互稿 06：录屏授权前说明卡。卡片措辞与系统弹窗一致（再认 < 回忆），建议选“整个屏幕”；
 * 声音偏好为开且尚未授权时，点“继续”（或跳过说明卡）后先申请 RECORD_AUDIO 再弹录屏授权，拒绝只投画面；
 * Android 14+ 每次会话都需授权，提供“下次不再提示”。
 * 另外按决策 #17 提示“锁屏会停止投屏，看剧建议用推送模式”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureIntroSheet(
    targetName: String,
    forceWholeScreen: Boolean = false,
    audioEnabled: Boolean,
    onAudioChange: (Boolean) -> Unit,
    onContinue: (skipNextTime: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var skip by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.sender_intro_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sender_intro_subtitle), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            ChoiceRow(true, stringResource(R.string.sender_intro_whole), stringResource(R.string.sender_intro_whole_detail), recommended = !forceWholeScreen)
            if (forceWholeScreen) {
                // 用户决策 D1：开启反向控制时只共享整个屏幕
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp), tint = c.primary)
                    Text(stringResource(R.string.sender_intro_control_forces_whole), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                ChoiceRow(false, stringResource(R.string.sender_intro_single), stringResource(R.string.sender_intro_single_detail))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = c.tertiary)
                Text(stringResource(R.string.sender_intro_encrypted, targetName), style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = audioEnabled, role = Role.Switch, onValueChange = onAudioChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sender_intro_audio), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.sender_intro_audio_detail), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Switch(checked = audioEnabled, onCheckedChange = null)
            }
            Row(
                Modifier.fillMaxWidth().background(WscTheme.extended.warningContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.LockClock, null, tint = WscTheme.extended.onWarningContainer)
                Text(stringResource(R.string.sender_intro_lock_warning), style = MaterialTheme.typography.bodySmall, color = WscTheme.extended.onWarningContainer)
            }
            // FlowRow：200% 字号时按钮换到下一行而不是被挤压（C11）
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(skip, role = Role.Checkbox) { skip = it }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skip, onCheckedChange = null)
                    Text(stringResource(R.string.sender_dont_show_again), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.sender_cancel)) }
                    Button(onClick = { onContinue(skip) }) { Text(stringResource(R.string.sender_continue)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String, recommended: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.primaryContainer else c.surfaceContainerLow, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // primaryContainer 底上只用 onPrimaryContainer（onSurfaceVariant 在该底上对比度不足）
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) c.onPrimaryContainer else c.onSurface)
                if (recommended) {
                    Text(
                        stringResource(R.string.sender_recommended),
                        Modifier.background(c.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.onPrimary,
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (selected) c.onPrimaryContainer else c.onSurfaceVariant)
        }
    }
}

/**
 * 交互稿 07：投屏中。胶囊每秒刷新、等宽数字；分段按钮切档即时生效（DataChannel SET_QUALITY）；
 * 停止按钮用 errorContainer（表达“终止”而不制造警报感）。
 */
@Composable
internal fun CastingScreen(
    state: SenderState.Casting,
    onQuality: (QualityProfile) -> Unit,
    onAutoAdjust: (Boolean) -> Unit,
    onAudio: (Boolean) -> Unit,
    onStop: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    showNotificationTip: Boolean = false,
    notificationNeedsSettings: Boolean = false,
    onAllowNotifications: () -> Unit = {},
    onDismissNotificationTip: () -> Unit = {},
    control: ControlUi = ControlUi(),
    onRevokeControl: () -> Unit = {},
    onContinueControl: () -> Unit = {},
    game: GameActions = GameActions(),
) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val elapsed = rememberElapsed(state.startedAt)
    // F18：点下即进入的切换态，胶囊与分段按钮共用（GameModeUi.kt）
    val qualitySwitch = rememberQualitySwitchUi(state)
    // 内容可滚动、“停止投屏”固定在页脚，任何字号 / 横屏下都在首屏（C10）
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceAvatar(Icons.Rounded.Tv)
            Column(Modifier.weight(1f)) {
                Text(state.target.name, style = MaterialTheme.typography.titleMedium)
                EncryptedLabel(stringResource(R.string.sender_mirroring))
            }
            Text(stringResource(R.string.sender_casting_badge), style = MaterialTheme.typography.labelLarge, color = ext.success)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val elapsedA11y = stringResource(R.string.sender_elapsed_a11y, elapsed)
            Text(elapsed, style = DisplayNumber, modifier = Modifier.semantics { contentDescription = elapsedA11y })
            val s = state.stats
            // F18 切换中：延迟段换成“切换中 均衡 → 游戏”，码率 / 分辨率段隐藏（交互稿 G02）
            val switchLabel = qualitySwitch.switching?.let { to -> stringResource((qualitySwitch.from ?: to).labelRes) + " → " + stringResource(to.labelRes) }
            // ≥150% 字号胶囊只留延迟段（交互稿 07 / 15，UX 走查 P1-2）
            val compact = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
            StatusCapsule(
                latencyMs = s.latencyMs,
                grade = s.grade,
                // 被控制中隐藏码率与分辨率段，给“被控制中”让出空间（UX 走查 G7）
                bitrateKbps = s.bitrateKbps.takeIf { it > 0 && !control.controlled && switchLabel == null && !compact },
                resolutionLabel = if (s.height > 0 && !control.controlled && switchLabel == null && !compact) "${minOf(s.width, s.height)}p${s.fps}" else null,
                switchingLabel = switchLabel,
                noAudio = state.audio == AudioState.NO_PERMISSION || state.audio == AudioState.OFF,
                controlled = control.controlled,
            )
            if (state.audio == AudioState.SILENT_SUSPECTED) {
                Text(stringResource(R.string.sender_audio_silent), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (!state.appVisible) {
                Text(stringResource(R.string.sender_app_hidden), style = MaterialTheme.typography.bodySmall, color = ext.warning)
            }
            if (state.protectedSuspected) {
                Text(stringResource(R.string.sender_protected), style = MaterialTheme.typography.bodySmall, color = ext.warning, textAlign = TextAlign.Center)
            }
        }
        if (control.controlled) ControlCard(control, onRevokeControl, onContinueControl)
        if (showNotificationTip) {
            // 可关闭的通知权限提示（S-c）
            Row(
                Modifier.fillMaxWidth().background(c.secondaryContainer, RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Notifications, null, tint = c.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sender_notification_tip), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.onSecondaryContainer)
                TextButton(onClick = onAllowNotifications) { Text(stringResource(if (notificationNeedsSettings) R.string.sender_open_settings else R.string.sender_notification_allow)) }
                IconButton(onClick = onDismissNotificationTip) { Icon(Icons.Rounded.Close, stringResource(R.string.sender_dismiss_tip), tint = c.onSecondaryContainer) }
            }
        }
        // F18 画质分段 + 切换中 + 档位说明 + 提示位 + G01（交互稿 15，GameModeUi.kt）
        QualitySection(state, onQuality, game, qualitySwitch)
        SwitchRow(stringResource(R.string.sender_auto_adjust), SenderCopy.autoAdjustDetail(state.profile).asString(), state.autoAdjust, onAutoAdjust)
        SwitchRow(
            stringResource(R.string.sender_audio),
            stringResource(if (state.audio == AudioState.NO_PERMISSION) R.string.sender_audio_no_permission else R.string.sender_audio_detail),
            state.audio != AudioState.OFF && state.audio != AudioState.NO_PERMISSION,
            onAudio,
            enabled = state.audio != AudioState.NO_PERMISSION,
        )
        if (state.audio == AudioState.NO_PERMISSION) {
            TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.sender_open_settings)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    StopFooter(onStop)
    }
}

/**
 * 交互稿 R04：被控制中的控制卡。primaryContainer 表达“正在发生的授权”而非警告；“收回控制”是卡内唯一按钮。
 */
@Composable
private fun ControlCard(control: ControlUi, onRevoke: () -> Unit, onContinueControl: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val since = rememberElapsed(control.grantedAt).removePrefix("00:")
    Column(
        Modifier.fillMaxWidth().background(c.primaryContainer, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.TouchApp, null, tint = c.onPrimaryContainer)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.sender_control_peer, control.peerName),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer,
                )
                val ownApp = control.phase == ControlPhase.PAUSED && control.reason == com.benchan.wireless.screencasting.core.protocol.control.ControlReason.OWN_APP
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val paused = control.phase == ControlPhase.PAUSED
                    // 暂停时副标题前加 warning 圆点（UX 走查 G14）
                    if (paused) Box(Modifier.size(8.dp).background(WscTheme.extended.warning, androidx.compose.foundation.shape.CircleShape))
                    Text(
                        when {
                            // D9：在本应用内时控制暂停，告诉用户原因与出路（用户决策）
                            ownApp -> stringResource(R.string.sender_control_own_app)
                            control.phase == ControlPhase.PAUSED && control.reason == com.benchan.wireless.screencasting.core.protocol.control.ControlReason.SENSITIVE_APP ->
                                stringResource(R.string.sender_control_sensitive)
                            paused -> stringResource(R.string.sender_control_paused, ControlCopy.pauseLabel(control.reason))
                            else -> stringResource(R.string.sender_control_since, since)
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = c.onPrimaryContainer,
                    )
                }
            }
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        // C 期：本机开着屏幕朗读时的提示，带 info 图标、实时监听（UX G4 / G5）
        if (com.benchan.wireless.screencasting.feature.control.ui.touchExploration()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onPrimaryContainer)
                Text(ControlCopy.TALKBACK_CARD, style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        val ownAppPaused = control.phase == ControlPhase.PAUSED && control.reason == com.benchan.wireless.screencasting.core.protocol.control.ControlReason.OWN_APP
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ownAppPaused) {
                // D9 暂停卡（交互稿 v1.0.3）：[回到桌面] Filled + [收回控制] Outlined
                Button(onClick = { ControlCopy.goHome(context) }, modifier = Modifier.heightIn(min = 40.dp)) { Text(stringResource(R.string.sender_control_go_home)) }
                OutlinedButton(onClick = onRevoke, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(painterResource(com.benchan.wireless.screencasting.feature.control.R.drawable.ic_control_handback), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sender_control_revoke))
                }
            } else if (control.phase == ControlPhase.PAUSED && control.reason == com.benchan.wireless.screencasting.core.protocol.control.ControlReason.SENSITIVE_APP) {
                // D-3 兜底：敏感界面暂停时本机可“继续控制”（例如权限弹窗需要对方继续）
                Button(onClick = onContinueControl, modifier = Modifier.heightIn(min = 40.dp)) { Text(stringResource(R.string.sender_control_continue)) }
                OutlinedButton(onClick = onRevoke, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(painterResource(com.benchan.wireless.screencasting.feature.control.R.drawable.ic_control_handback), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sender_control_revoke))
                }
            } else {
                Button(onClick = onRevoke, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(painterResource(com.benchan.wireless.screencasting.feature.control.R.drawable.ic_control_handback), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sender_control_revoke))
                }
            }
        }
    }
}

/** 固定在页脚的“停止投屏”（07 / 08A 共用）。 */
@Composable
private fun StopFooter(onStop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onStop,
        modifier = Modifier.reportFooterHeight().fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer),
    ) {
        Icon(Icons.Rounded.Stop, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.sender_stop_casting))
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 交互稿 08A：断线重连中。文案三段式：发生了什么 → 系统在做什么 → 你不需要做什么；进度条按 30 秒上限线性推进。 */
@Composable
internal fun ReconnectingScreen(state: SenderState.Reconnecting, onStop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val waited = ((now - state.since) / 1000).toInt()
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(ext.warningContainer, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 读屏：标题变化时礼貌播报；秒数类文字不设 liveRegion，避免每秒打断（C13）
            Text(
                stringResource(R.string.sender_reconnecting_attempt, state.attempt.coerceAtLeast(1)),
                style = MaterialTheme.typography.titleLarge, color = ext.onWarningContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(stringResource(R.string.sender_reconnecting_body), style = MaterialTheme.typography.bodyMedium, color = ext.onWarningContainer)
            LinearProgressIndicator(
                progress = { (now - state.since).toFloat() / WscProtocol.RECONNECT_WINDOW_MS },
                modifier = Modifier.fillMaxWidth(),
                color = ext.warning,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.sender_reconnecting_waited, waited, waited), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ext.onWarningContainer)
                Text(stringResource(R.string.sender_reconnecting_max), style = MaterialTheme.typography.bodySmall, color = ext.onWarningContainer)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceAvatar(Icons.Rounded.Tv)
            Column(Modifier.weight(1f)) {
                Text(state.target.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.sender_reconnecting_last_frame), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        StatusCapsule(null, null, null, reconnectingLabel = stringResource(R.string.sender_reconnecting_capsule))
        state.nextRetryAt?.let {
            val s = ((it - now) / 1000).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sender_next_retry, if (s == 0L) stringResource(R.string.sender_next_retry_now) else pluralStringResource(R.plurals.sender_next_retry_in, s.toInt(), s.toInt())), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    StopFooter(onStop)
    }
}

/**
 * 结束类状态的整页提示：08B 重连失败、锁屏已停止（决策 #17）、录屏授权被拒、协议版本不兼容、系统停止录屏。
 * 失败页用 errorContainer 图标而非整页红色：失败是结果，不是危险。
 */
@Composable
internal fun EndedScreen(
    state: SenderState.Ended,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
    /** 08B / 录屏被拒页的 [改用推送]（交互稿 13 章“与其他章节的关系”，第 1 轮 PM P1-9）。 */
    onUsePush: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val name = state.target?.name ?: stringResource(R.string.sender_tv_fallback)
    data class Copy(val title: String, val detail: String, val primary: String?, val note: String?)
    val copy = when (state.reason) {
        EndReason.RECONNECT_FAILED -> Copy(
            stringResource(R.string.sender_end_disconnected), stringResource(R.string.sender_end_reconnect_failed_detail, name),
            stringResource(R.string.sender_end_reconnect), stringResource(R.string.sender_end_reconnect_note),
        )
        EndReason.SCREEN_LOCKED -> Copy(
            stringResource(R.string.sender_end_locked), stringResource(R.string.sender_end_locked_detail),
            stringResource(R.string.sender_end_recast), stringResource(R.string.sender_end_recast_note),
        )
        EndReason.PROJECTION_STOPPED -> Copy(stringResource(R.string.sender_end_projection_stopped), stringResource(R.string.sender_end_projection_stopped_detail), stringResource(R.string.sender_end_recast), stringResource(R.string.sender_end_recast_note))
        EndReason.CAPTURE_DENIED -> Copy(stringResource(R.string.sender_end_capture_denied), stringResource(R.string.sender_end_capture_denied_detail), stringResource(R.string.sender_end_retry), stringResource(R.string.sender_end_capture_denied_note))
        EndReason.VERSION_UNSUPPORTED -> Copy(stringResource(R.string.sender_end_version), stringResource(R.string.sender_end_version_detail, name), null, null)
        else -> Copy(stringResource(R.string.sender_end_disconnected), state.detail ?: "", stringResource(R.string.sender_end_reconnect), null)
    }
    // 可滚动的同时保持垂直居中：内容最小高度 = 可视高度（S8）
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
    val minH = maxHeight
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minH).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(72.dp).background(c.errorContainer, RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
            Icon(
                when (state.reason) {
                    EndReason.SCREEN_LOCKED -> Icons.Rounded.LockClock
                    EndReason.CAPTURE_DENIED, EndReason.PROJECTION_STOPPED -> Icons.Rounded.VideocamOff
                    else -> Icons.Rounded.ErrorOutline
                },
                null, Modifier.size(36.dp), tint = c.onErrorContainer,
            )
        }
        Text(copy.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(copy.detail, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Start)
        if (copy.primary != null && state.target != null) {
            Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text(copy.primary)
            }
            copy.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
        if (onUsePush != null && (state.reason == EndReason.SCREEN_LOCKED || state.reason == EndReason.CAPTURE_DENIED)) {
            FilledTonalButton(onClick = onUsePush, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.sender_use_push)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.sender_back_to_devices)) }
            if (state.reason == EndReason.RECONNECT_FAILED) TextButton(onClick = onDiagnostics) { Text(stringResource(R.string.sender_action_diagnostics)) }
        }
    }
    }
}

@Composable
internal fun rememberElapsed(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
