package com.benchan.wireless.screencasting.feature.sender.elder

import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.ui.theme.ElderDimens
import com.benchan.wireless.screencasting.core.ui.theme.LocalElderTypography
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 长辈按钮的视觉层级（交互稿 E01：primary 实心 > secondaryContainer > Outlined > 文字按钮；停止 = errorContainer）。 */
enum class ElderButtonKind { PRIMARY, SECONDARY, OUTLINED, STOP, TEXT }

/**
 * 长辈大按钮：≥64 dp、文字一律可见（不用只有图标的按钮）、文字可换行（200% 字号时按钮随内容长高）。
 * 颜色只用 on* 组合（≥7:1）；不可用时文字用 outline、底用 surfaceContainerHigh 实色（UX P1-3）。
 */
@Composable
fun ElderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ElderButtonKind = ElderButtonKind.PRIMARY,
    sub: String? = null,
    minHeight: Dp = ElderDimens.MinTouch,
    enabled: Boolean = true,
    spoken: String? = null,
    icon: ImageVector? = null,
    alignStart: Boolean = false,
) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val base = modifier.fillMaxWidth().heightIn(min = minHeight).let { m -> spoken?.let { s -> m.semantics { contentDescription = s } } ?: m }
    val label = @Composable { fg: Color ->
        val color = if (enabled) fg else c.outline
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignStart) Arrangement.Start else Arrangement.Center) {
            icon?.let {
                Icon(it, null, Modifier.size(28.dp), tint = color)
                Spacer(Modifier.width(12.dp))
            }
            Column(horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally, modifier = if (alignStart) Modifier.weight(1f) else Modifier) {
                Text(text, style = if (minHeight >= ElderDimens.SecondaryButton && !alignStart) t.title else t.label, color = color, textAlign = if (alignStart) TextAlign.Start else TextAlign.Center)
                sub?.let { Text(it, style = t.bodySmall, color = color, textAlign = if (alignStart) TextAlign.Start else TextAlign.Center) }
            }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    val dBg = c.surfaceContainerHigh
    val dFg = c.outline
    when (kind) {
        ElderButtonKind.PRIMARY -> Button(onClick, base, enabled = enabled, shape = shape, colors = ButtonDefaults.buttonColors(c.primary, c.onPrimary, dBg, dFg)) { label(c.onPrimary) }
        ElderButtonKind.SECONDARY -> Button(onClick, base, enabled = enabled, shape = shape, colors = ButtonDefaults.buttonColors(c.secondaryContainer, c.onSecondaryContainer, dBg, dFg)) { label(c.onSecondaryContainer) }
        ElderButtonKind.STOP -> Button(onClick, base, enabled = enabled, shape = shape, colors = ButtonDefaults.buttonColors(c.errorContainer, c.onErrorContainer, dBg, dFg)) { label(c.onErrorContainer) }
        ElderButtonKind.OUTLINED -> OutlinedButton(onClick, base, enabled = enabled, shape = shape) { label(c.onSurface) }
        ElderButtonKind.TEXT -> TextButton(onClick, base, enabled = enabled) { label(c.onSurface) }
    }
}

/**
 * 长辈页面骨架：顶部带文字的返回 / 取消（64 dp），内容可滚动，主操作固定在页脚可见区（交互稿“系统字体 200%”）。
 * 每页设 paneTitle（换页时读屏播报），打开时焦点在标题（UX P1-1）。
 * @param topBar 顶栏小字（首页“局域投屏”，不是标题）
 */
@Composable
fun ElderPage(
    title: String?,
    back: String? = null,
    onBack: (() -> Unit)? = null,
    topBar: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(title) { if (title != null) runCatching { focus.requestFocus() } }
    val appName = stringResource(R.string.elder_app_name)
    Column(
        Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding()
            .semantics { paneTitle = title ?: topBar ?: appName },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null && onBack != null) {
                TextButton(onClick = onBack, Modifier.padding(top = 4.dp).heightIn(min = ElderDimens.MinTouch)) {
                    Text(back, style = t.label, color = c.onSurface)
                }
            }
            topBar?.let { Text(it, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), style = t.bodySmall, color = c.onSurfaceVariant) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(ElderDimens.Gap),
        ) {
            title?.let { Text(it, Modifier.focusRequester(focus).focusable().semantics { heading() }, style = t.headline, color = c.onSurface) }
            content()
        }
        footer?.let {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(ElderDimens.Gap), content = it)
        }
    }
}

/** 状态行：转圈 / 绿点 / warning 三角只做冗余提示（形状也区分），文字一律 onSurface（交互稿 E01）。 */
@Composable
fun ElderStatusLine(found: Boolean?, text: String) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (found) {
            null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            true -> Box(Modifier.size(14.dp).background(WscTheme.extended.success, CircleShape))
            false -> Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp), tint = WscTheme.extended.warning)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = t.body, color = c.onSurface)
    }
}

/** 常驻卡片（不用自动消失的 Toast / Snackbar）。 */
@Composable
fun ElderCard(title: String, body: String? = null, container: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: Color = MaterialTheme.colorScheme.onSurface, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val t = LocalElderTypography.current
    Column(
        Modifier.fillMaxWidth().background(container, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, style = t.title, color = content)
        body?.let { Text(it, style = t.body, color = content) }
        extra?.invoke(this)
    }
}

/**
 * 按住 3 秒才生效的按钮（决策 #40，交互稿 E11）：按下时进度环线性走满 3 秒，松手早于 3 秒退回并常驻提示；
 * 读屏：双击只提示“需要按住 3 秒，或使用操作菜单”，操作菜单（自定义操作）直接触发；
 * 外接键盘 / 开关控制可聚焦，按住 Enter / 空格 3 秒同样生效（UX P1-2）。
 */
@Composable
fun HoldToConfirmButton(title: String, sub: String, actionLabel: String, onConfirmed: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val remainingSec = (3 - (progress.value * 3).toInt()).coerceIn(1, 3)
    val subText = when {
        holding -> stringResource(R.string.elder_hold_keep, remainingSec)
        released -> stringResource(R.string.elder_hold_needed, actionLabel)
        else -> sub
    }
    val holdCd = stringResource(R.string.elder_hold_cd, title)
    val holdAnnounce = stringResource(R.string.elder_hold_announce)
    val hintLabel = stringResource(R.string.elder_hint)
    fun begin() {
        if (job?.isActive == true) return
        holding = true
        released = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
            holding = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            progress.snapTo(0f)
            onConfirmed()
        }
    }
    fun end() {
        if (job?.isActive == true) {
            job?.cancel()
            holding = false
            released = true
            scope.launch { progress.animateTo(0f, tween(200)) }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = ElderDimens.SecondaryButton)
            .background(if (holding) c.surfaceContainerHigh else c.surfaceContainerLow, RoundedCornerShape(20.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = holdCd
                stateDescription = subText
                customActions = listOf(CustomAccessibilityAction(actionLabel) { onConfirmed(); true })
                onClick(label = hintLabel) {
                    view.announceForAccessibility(holdAnnounce)
                    true
                }
            }
            .onPreviewKeyEvent { e ->
                val k = e.key
                if (k != Key.Enter && k != Key.Spacebar && k != Key.NumPadEnter && k != Key.DirectionCenter) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) begin() else if (e.type == KeyEventType.KeyUp) end()
                true
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    begin()
                    tryAwaitRelease()
                    end()
                })
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { 1f }, Modifier.size(36.dp), color = c.outlineVariant, strokeWidth = 4.dp)
            CircularProgressIndicator(progress = { progress.value }, Modifier.size(36.dp), color = c.onSurface, strokeWidth = 4.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Text(title, style = t.label, color = c.onSurface)
            Text(subText, style = t.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

private const val HOLD_MS = 3_000

/** 大号数字键盘（每键 72 dp、写“删除”不画图标；最后一行 空 · 0 · 删除，0 在 8 正下方，UX P1-6）。 */
@Composable
fun ElderKeypad(onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val rows = listOf("123", "456", "789", " 0D")
    val delete = stringResource(R.string.elder_delete)
    Column(Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(20.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEach { ch ->
                    when (ch) {
                        ' ' -> Spacer(Modifier.weight(1f))
                        'D' -> Button(
                            onClick = onDelete,
                            Modifier.weight(1f).heightIn(min = ElderDimens.Key),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(c.surfaceContainerLowest, c.onSurface),
                        ) { Text(delete, style = t.label) }
                        else -> Button(
                            onClick = { onDigit(ch) },
                            Modifier.weight(1f).heightIn(min = ElderDimens.Key),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(c.surfaceContainerLowest, c.onSurface),
                        ) { Text(ch.toString(), style = t.digitKey) }
                    }
                }
            }
        }
    }
}
