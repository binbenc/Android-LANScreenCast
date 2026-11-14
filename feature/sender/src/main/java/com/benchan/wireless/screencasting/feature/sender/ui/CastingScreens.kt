package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
 * 声音开关首次打开时才申请 RECORD_AUDIO；Android 14+ 每次会话都需授权，提供“下次不再提示”。
 * 另外按决策 #17 提示“锁屏会停止投屏，看剧建议用推送模式”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureIntroSheet(
    targetName: String,
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
            Text("开始镜像前，还有一步", style = MaterialTheme.typography.titleLarge)
            Text("系统会询问是否允许录屏，请选择：", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            ChoiceRow(true, "共享整个屏幕", "切换 App 时电视画面不中断", recommended = true)
            ChoiceRow(false, "共享单个应用", "离开该应用后，电视会显示占位画面")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = c.tertiary)
                Text("画面加密传输，只发送到 $targetName", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = audioEnabled, role = Role.Switch, onValueChange = onAudioChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("同时投送声音", style = MaterialTheme.typography.bodyLarge)
                    Text("只捕获手机播放的声音，不录麦克风", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Switch(checked = audioEnabled, onCheckedChange = null)
            }
            Row(
                Modifier.fillMaxWidth().background(WscTheme.extended.warningContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.LockClock, null, tint = WscTheme.extended.onWarningContainer)
                Text("锁屏会停止投屏。镜像期间手机会保持亮屏；看剧建议用视频 App 的“投屏”按钮推送。", style = MaterialTheme.typography.bodySmall, color = WscTheme.extended.onWarningContainer)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).toggleable(skip, role = Role.Checkbox) { skip = it }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skip, onCheckedChange = null)
                    Text("下次不再提示", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onContinue(skip) }) { Text("继续") }
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
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (recommended) Text("推荐", style = MaterialTheme.typography.labelSmall, color = c.primary)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
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
) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val elapsed = rememberElapsed(state.startedAt)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
                EncryptedLabel("WSC")
            }
            Text("投屏中", style = MaterialTheme.typography.labelLarge, color = ext.success)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(elapsed, style = DisplayNumber, modifier = Modifier.semantics { contentDescription = "已投屏 $elapsed" })
            val s = state.stats
            StatusCapsule(
                latencyMs = s.latencyMs,
                grade = s.grade,
                bitrateKbps = s.bitrateKbps.takeIf { it > 0 },
                resolutionLabel = if (s.height > 0) "${minOf(s.width, s.height)}p${s.fps}" else null,
                noAudio = state.audio == AudioState.NO_PERMISSION || state.audio == AudioState.OFF,
            )
            if (state.audio == AudioState.SILENT_SUSPECTED) {
                Text("一直没有声音：正在播放的 App 可能禁止了声音捕获", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (!state.appVisible) {
                Text("被共享的应用已切到后台，电视显示占位画面", style = MaterialTheme.typography.bodySmall, color = ext.warning)
            }
            if (state.protectedSuspected) {
                Text("当前画面可能受保护（黑屏），建议改用视频 App 的“投屏”按钮推送", style = MaterialTheme.typography.bodySmall, color = ext.warning, textAlign = TextAlign.Center)
            }
        }
        Text("画质", style = MaterialTheme.typography.titleSmall, color = c.primary)
        val profiles = listOf(QualityProfile.SMOOTH, QualityProfile.BALANCED, QualityProfile.HD, QualityProfile.GAME)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = state.profile == p,
                    onClick = { onQuality(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                    icon = { if (p == QualityProfile.GAME) Icon(Icons.Rounded.SportsEsports, null, Modifier.size(16.dp)) else SegmentedButtonDefaults.Icon(state.profile == p) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(p.label) }
            }
        }
        Text(
            state.profile.summary + when (state.profile) {
                QualityProfile.SMOOTH -> "，网络一般时更稳定"
                QualityProfile.BALANCED -> "，适合日常镜像"
                QualityProfile.HD -> "，需要较好的 5GHz 网络"
                QualityProfile.GAME -> "，延迟优先（声音可能略晚于画面）"
            },
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
        )
        SwitchRow("自动调节", "网络变差时自动降为“流畅”", state.autoAdjust, onAutoAdjust)
        SwitchRow(
            "声音",
            if (state.audio == AudioState.NO_PERMISSION) "未授予录音权限，只投画面（可在系统设置中开启）" else "在电视上播放手机的声音",
            state.audio != AudioState.OFF && state.audio != AudioState.NO_PERMISSION,
            onAudio,
            enabled = state.audio != AudioState.NO_PERMISSION,
        )
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer),
        ) {
            Icon(Icons.Rounded.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("停止投屏")
        }
        Spacer(Modifier.height(24.dp))
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.fillMaxWidth().background(ext.warningContainer, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("正在重连（第 ${state.attempt.coerceAtLeast(1)} 次）", style = MaterialTheme.typography.titleLarge, color = ext.onWarningContainer)
            Text("网络中断了一下。录屏没有停止，恢复后会自动继续，不需要重新授权。", style = MaterialTheme.typography.bodyMedium, color = ext.onWarningContainer)
            LinearProgressIndicator(
                progress = { (now - state.since).toFloat() / WscProtocol.RECONNECT_WINDOW_MS },
                modifier = Modifier.fillMaxWidth(),
                color = ext.warning,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已等待 $waited 秒", style = MaterialTheme.typography.bodySmall, color = ext.onWarningContainer)
                Text("最长 30 秒", style = MaterialTheme.typography.bodySmall, color = ext.onWarningContainer)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceAvatar(Icons.Rounded.Tv)
            Column(Modifier.weight(1f)) {
                Text(state.target.name, style = MaterialTheme.typography.titleMedium)
                Text("电视正保留最后一帧画面", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        StatusCapsule(null, null, null, reconnectingLabel = "— ms 已暂停")
        state.nextRetryAt?.let {
            val s = ((it - now) / 1000).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("下一次尝试：${if (s == 0L) "正在尝试" else "$s 秒后"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer),
        ) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("停止投屏") }
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
) {
    val c = MaterialTheme.colorScheme
    val name = state.target?.name ?: "电视"
    data class Copy(val title: String, val detail: String, val primary: String?, val note: String?)
    val copy = when (state.reason) {
        EndReason.RECONNECT_FAILED -> Copy(
            "连接已断开", "30 秒内没能重新连上 $name\n\n可能的原因\n· 电视已关机，或退出了接收页面\n· 手机或电视切换到了其他 Wi‑Fi\n· 路由器重启",
            "重新连接", "系统要求重新允许一次录屏（Android 14 起）",
        )
        EndReason.SCREEN_LOCKED -> Copy(
            "锁屏已停止投屏", "手机锁屏后，系统会自动停止录屏（Android 15 起）。解锁后点一下即可重新投屏。\n\n看剧建议用视频 App 的“投屏”按钮推送，手机可以锁屏。",
            "重新投屏", "需要重新允许一次录屏",
        )
        EndReason.PROJECTION_STOPPED -> Copy("录屏已停止", "录屏被系统或状态栏停止了。", "重新投屏", "需要重新允许一次录屏")
        EndReason.CAPTURE_DENIED -> Copy("需要允许录屏才能镜像", "系统弹窗中选择“开始”后，电视才能看到手机画面。", "重试", "看剧时也可以改用视频 App 的“投屏”按钮推送（下个版本支持从本应用推送）")
        EndReason.VERSION_UNSUPPORTED -> Copy("对方版本过旧", "请升级 $name 上的接收端后再试。", null, null)
        else -> Copy("连接已断开", state.detail ?: "", "重新连接", null)
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回设备列表") }
            if (state.reason == EndReason.RECONNECT_FAILED) TextButton(onClick = onDiagnostics) { Text("网络诊断") }
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

/** 结束原因 → Snackbar 文案（不需要整页的情况）。 */
internal fun EndReason.snackbarText(name: String?): String? = when (this) {
    EndReason.REJECTED -> "对方拒绝了投屏请求"
    EndReason.CONFIRM_TIMEOUT -> "${name ?: "电视"} 没有响应，请重试"
    EndReason.PAIR_LOCKED -> "尝试次数过多，请 60 秒后再试"
    EndReason.CONNECT_FAILED -> "找不到 ${name ?: "该设备"}，请确认电视已打开接收页面"
    EndReason.CERTIFICATE_MISMATCH -> "设备身份校验失败：可能是其他设备冒充，或接收端重装过（可在设置中移除信任后重新配对）"
    EndReason.BUSY -> "对方正忙"
    EndReason.REMOTE_CLOSED -> "${name ?: "电视"} 已断开投屏"
    EndReason.INTERNAL_ERROR -> "连接出错了，请重试"
    else -> null
}
