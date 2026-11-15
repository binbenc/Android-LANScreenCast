package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.compose.ui.text.style.TextOverflow
import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.quality.GameHint
import com.benchan.wireless.screencasting.core.protocol.quality.GameHintType
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.ui.components.GameIntroDialog
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.sender.session.SenderState

/** 投屏中页里 F18 游戏模式的回调（交互稿 15）。 */
internal data class GameActions(
    val onIntroResult: (Boolean) -> Unit = {},
    val onHintDismiss: (GameHintType) -> Unit = {},
    val onHintAction: (GameHintType) -> Unit = {},
    val onOpenInstallHelp: () -> Unit = {},
)

private val PROFILES = listOf(QualityProfile.SMOOTH, QualityProfile.BALANCED, QualityProfile.HD, QualityProfile.GAME)

/**
 * 点下后的即时切换态（G02：100 ms 内反馈）：本地先记住“正在切到哪一档”，控制器状态到达后以它为准。
 * 胶囊和分段按钮共用同一个值，避免“按钮在转圈、胶囊还显示数值”（UX 走查 P2-6）。
 */
@Stable
internal class QualitySwitchUi(state: SenderState.Casting) {
    /** 最新的投屏状态（每次重组时更新；普通字段，读它的地方本来就随状态重组）。 */
    var state: SenderState.Casting = state

    var tapped by mutableStateOf<QualityProfile?>(null)

    val switching: QualityProfile?
        get() = state.switchingTo ?: tapped?.takeIf { it != state.profile && !state.gameIntroPending }

    val from: QualityProfile?
        get() = if (state.switchingTo != null) state.switchingFrom else tapped?.let { state.profile }
}

@Composable
internal fun rememberQualitySwitchUi(state: SenderState.Casting): QualitySwitchUi {
    val ui = remember { QualitySwitchUi(state) }
    ui.state = state
    LaunchedEffect(state.switchingTo, state.profile, state.gameIntroPending) { ui.tapped = null }
    // 兜底：控制器没有进入切换（例如被忽略）时不让转圈一直挂着
    LaunchedEffect(ui.tapped) { if (ui.tapped != null) { kotlinx.coroutines.delay(1_000); ui.tapped = null } }
    return ui
}

/**
 * 交互稿 07 “画质”区 + 15 G01–G04：分段按钮（切换中转圈、锁住其他档位）、档位说明、实测值、提示位、首次说明卡。
 * 停止投屏 / 自动调节 / 声音不在这里，切换中照常可用（停止永远不能被锁住）。
 * 组内间距 8 dp（说明紧贴按钮、实测值 4 dp、提示卡 12 dp，UX 走查 P2-4）；切换前后按钮以上的布局高度不变（UX 走查 P1-1）。
 */
@Composable
internal fun QualitySection(state: SenderState.Casting, onQuality: (QualityProfile) -> Unit, game: GameActions, sw: QualitySwitchUi) {
    val c = MaterialTheme.colorScheme
    val switching = sw.switching
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sender_quality_title), style = MaterialTheme.typography.titleSmall, color = c.primary)
        val select: (QualityProfile) -> Unit = { p ->
            if (state.switchingTo == null) {
                val needsIntro = p.lowLatency && !state.gameIntroAcknowledged
                if (p != state.profile && QualityProfile.needsMediaRebuild(state.profile, p) && !state.receiverLegacy && !needsIntro) sw.tapped = p
                onQuality(p)
            }
        }
        // ≥150% 字号：2×2 网格（每格 ≥48 dp，交互稿 15 “200% 字号要点”）
        if (LocalDensity.current.fontScale >= 1.5f) {
            PROFILES.chunked(2).forEach { pair ->
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    pair.forEachIndexed { i, p -> QualityButton(p, i, 2, state.profile, switching, select) }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PROFILES.forEachIndexed { i, p -> QualityButton(p, i, PROFILES.size, state.profile, switching, select) }
            }
        }
        DescriptionLine(state, switching)
        // 这台接收端以前测过游戏模式：做决定前也显示（层级 ⑤，UX 走查 P2-1）
        state.measuredGameLatencyMs?.takeIf { switching == null }?.let { ms ->
            val gameNow = state.profile == QualityProfile.GAME
            val text = SenderCopy.measuredLatency(ms, gameNow).asString()
            val a11y = SenderCopy.measuredLatencyA11y(ms, gameNow).asString()
            Row(
                Modifier.offset(y = (-4).dp).semantics(mergeDescendants = true) { contentDescription = a11y },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.History, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
            }
        }
        state.hint?.let { Box(Modifier.padding(top = 4.dp)) { HintCard(it, game) } }
    }
    if (state.gameIntroPending) GameIntroDialog(game.onIntroResult)
}

/**
 * 说明行：切换中 = “正在切换到游戏模式，电视画面会停一下”（回退时“正在恢复‘均衡’…”）；完成时短暂改为“已切换到游戏模式”并 polite 播报，
 * 1.5 秒后恢复档位说明（liveRegion 只在这两种状态挂上，不额外放占位节点，UX 走查 P1-6）。
 */
@Composable
private fun DescriptionLine(state: SenderState.Casting, switching: QualityProfile?) {
    val c = MaterialTheme.colorScheme
    var last by remember { mutableStateOf<QualityProfile?>(null) }
    var done by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(switching) {
        val target = last
        last = switching
        if (switching == null && target != null && target == state.profile && !state.switchingRollback) {
            done = SenderCopy.qualitySwitched(target)
            kotlinx.coroutines.delay(1_500)
            done = null
        }
    }
    val audioOn = (state.audio == AudioState.ON || state.audio == AudioState.SILENT_SUSPECTED) && state.audioSplit
    val (text, live, color) = when {
        switching != null && state.switchingRollback -> Triple(SenderCopy.qualityRestoring(switching).asString(), true, c.onSurface)
        switching != null -> Triple(SenderCopy.qualitySwitching(switching).asString(), true, c.onSurface)
        done != null -> Triple(done!!.asString(), true, c.onSurface)
        else -> Triple(SenderCopy.qualityDescription(state.profile, audioOn, state.fpsCapped).asString(), false, c.onSurfaceVariant)
    }
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = color,
        modifier = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
    )
}

@Composable
private fun androidx.compose.material3.SingleChoiceSegmentedButtonRowScope.QualityButton(
    p: QualityProfile,
    index: Int,
    count: Int,
    current: QualityProfile,
    switching: QualityProfile?,
    onClick: (QualityProfile) -> Unit,
) {
    val isTarget = switching == p
    val locked = switching != null && !isTarget
    val switchingDesc = stringResource(R.string.sender_quality_state_switching)
    val lockedDesc = stringResource(R.string.sender_quality_state_locked)
    SegmentedButton(
        selected = if (switching != null) isTarget else current == p,
        onClick = { onClick(p) },
        // 切换完成前锁住其他档位（防止连点造成多次重建，G02）
        enabled = !locked,
        shape = SegmentedButtonDefaults.itemShape(index, count),
        // 切换中：目标档 secondaryContainer；被锁的档位用 outline（G02，UX 走查 P2-17）
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            disabledInactiveContentColor = MaterialTheme.colorScheme.outline,
            disabledInactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        icon = {
            when {
                // 被点的档位：16 dp 转圈代替勾，不改变宽度
                isTarget -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                p == QualityProfile.GAME -> Icon(Icons.Rounded.SportsEsports, null, Modifier.size(16.dp))
                else -> SegmentedButtonDefaults.Icon(current == p && switching == null)
            }
        },
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            if (isTarget) stateDescription = switchingDesc else if (locked) stateDescription = lockedDesc
        },
    ) { Text(stringResource(p.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * G04 提示位：一次一张，常驻直到 [知道了] 或条件消失；② ③（系统替用户改了设置）用 warningContainer，其余中性卡。
 * 出现时 polite 播报标题，不抢焦点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HintCard(h: GameHint, game: GameActions) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val copy = SenderCopy.hint(h)
    val bg = if (h.warning) ext.warningContainer else c.surfaceContainerHigh
    val fg = if (h.warning) ext.onWarningContainer else c.onSurface
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        // 标题 + 说明合成一个读屏分组，按钮在分组外独立可聚焦（UX 走查 P2-3）
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(hintIcon(h.type), null, Modifier.size(18.dp), tint = if (h.warning) fg else c.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(copy.title.asString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = fg)
                copy.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = if (h.warning) fg else c.onSurfaceVariant) }
            }
        }
        // 按钮放不下时换行（200% 字号）
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            copy.action?.let { labelRes ->
                TextButton(onClick = {
                    if (h.type == GameHintType.LEGACY_RECEIVER) {
                        game.onHintDismiss(h.type)
                        game.onOpenInstallHelp()
                    } else {
                        game.onHintAction(h.type)
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(labelRes)) }
            }
            TextButton(onClick = { game.onHintDismiss(h.type) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sender_hint_dismiss)) }
        }
    }
}

/** 提示类型 → 图标（交互稿 G04：gauge / thermo / game / wifi / vol / battery / info；②③ 也不用三角警告，UX 走查 P2-2）。 */
private fun hintIcon(t: GameHintType): ImageVector = when (t) {
    GameHintType.THERMAL_30FPS -> Icons.Rounded.Thermostat
    GameHintType.FPS_30 -> Icons.Rounded.SportsEsports
    GameHintType.LATENCY_UNMET -> Icons.Rounded.Speed
    GameHintType.POOR_NETWORK -> Icons.Rounded.Wifi
    GameHintType.AUDIO_LATE -> Icons.AutoMirrored.Rounded.VolumeUp
    GameHintType.LOW_BATTERY -> Icons.Rounded.BatteryAlert
    GameHintType.LEGACY_RECEIVER -> Icons.Rounded.SystemUpdate
    GameHintType.SWITCH_FAILED, GameHintType.NEXT_TIME -> Icons.Rounded.Info
}
