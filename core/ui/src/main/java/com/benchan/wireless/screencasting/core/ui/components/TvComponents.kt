package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor as TvLocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * TV 焦点态（交互稿 07 TV 焦点态规范）四重冗余编码：
 * 反相填充（onSurface 底 + inverseOnSurface 字）、缩放（按钮 1.08 / 卡片 1.05）、外环（3dp 间隙 + 2dp primary）、
 * 光晕（primary @ 32%，6dp）；按下 scale 1.02 去光晕保留外环；
 * 不可用：surfaceContainer 底 + outline 字（不叠加透明度）。
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 主操作（primary 填充，例如屏 12 的“允许”）；聚焦态仍统一反相。 */
    emphasis: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
    /** 已选中（例如反向控制的当前模式）：primaryContainer 底 + 前置 ✓ + 读屏“已选中”。 */
    selected: Boolean = false,
    /** 不可用但仍可聚焦（例如对方系统版本不支持）：surfaceContainer 底 + outline 字，按下由调用方说明原因。 */
    unavailable: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val reduced = rememberReducedMotion()
    Button(
        onClick = onClick,
        modifier = modifier.tvMinDrawSize().touchClickable(enabled, interaction, onClick).heightIn(min = 48.dp)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = CircleShape),
        scale = if (reduced) ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f) else ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1.02f),
        interactionSource = interaction,
        colors = ButtonDefaults.colors(
            containerColor = when {
                unavailable -> c.surfaceContainer
                selected -> c.primaryContainer
                emphasis -> c.primary
                else -> c.surfaceContainerHighest
            },
            contentColor = when {
                unavailable -> c.outline
                selected -> c.onPrimaryContainer
                emphasis -> c.onPrimary
                else -> c.onSurface
            },
            focusedContainerColor = c.onSurface,
            focusedContentColor = c.inverseOnSurface,
            pressedContainerColor = c.onSurface,
            pressedContentColor = c.inverseOnSurface,
            disabledContainerColor = c.surfaceContainer,
            disabledContentColor = c.outline,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, c.primary), inset = (-3).dp, shape = CircleShape),
            pressedBorder = Border(BorderStroke(2.dp, c.primary), inset = (-3).dp, shape = CircleShape),
        ),
        glow = ButtonDefaults.glow(focusedGlow = Glow(c.primary.copy(alpha = 0.32f), 6.dp)),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    ) {
        // tv Button 只设置 tv 的 LocalContentColor；同步给 M3，leading 里未写 tint 的 M3 Icon 才会随聚焦 / 不可用态反相
        CompositionLocalProvider(LocalContentColor provides TvLocalContentColor.current) {
            if (selected && leading == null) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, Modifier.size(18.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            }
            leading?.invoke(this)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * TV 图标按钮（固定 48 dp 方形，例如控制条的关闭 ×）：与 [TvButton] 同一焦点规范；尺寸固定，边框 / 光晕永远有正的绘制区域。
 */
@Composable
fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val reduced = rememberReducedMotion()
    Button(
        onClick = onClick,
        modifier = modifier.tvMinDrawSize().size(48.dp).touchClickable(true, interaction, onClick)
            .semantics { this.contentDescription = contentDescription },
        shape = ButtonDefaults.shape(shape = CircleShape),
        scale = if (reduced) ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f) else ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1.02f),
        interactionSource = interaction,
        colors = ButtonDefaults.colors(
            containerColor = c.surfaceContainerHighest,
            contentColor = c.onSurface,
            focusedContainerColor = c.onSurface,
            focusedContentColor = c.inverseOnSurface,
            pressedContainerColor = c.onSurface,
            pressedContentColor = c.inverseOnSurface,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, c.primary), inset = (-3).dp, shape = CircleShape),
            pressedBorder = Border(BorderStroke(2.dp, c.primary), inset = (-3).dp, shape = CircleShape),
        ),
        glow = ButtonDefaults.glow(focusedGlow = Glow(c.primary.copy(alpha = 0.32f), 6.dp)),
        contentPadding = PaddingValues(0.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides TvLocalContentColor.current) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(24.dp))
        }
    }
}

/**
 * tv-material 的 Surface 绘制边框 / 光晕时按边框宽度向内 inset，绘制区域小于边框宽度会抛
 * “Width and height must be greater than or equal to zero”（例如 Row 里放不下的按钮被测量为 0 宽）。
 * 本修饰符保证按钮至少按 [min]（48 dp，触控 / 焦点最小尺寸）测量与绘制：父布局给的最大宽高小于它时仍以 [min] 测量
 * （超出部分居中溢出，不会出现负尺寸）。正常布局（父布局给足空间）下不改变任何尺寸。
 */
fun Modifier.tvMinDrawSize(min: androidx.compose.ui.unit.Dp = 48.dp): Modifier = this.layout { measurable, constraints ->
    val minPx = min.roundToPx()
    val inner = constraints.copy(
        maxWidth = maxOf(constraints.maxWidth, minPx),
        maxHeight = maxOf(constraints.maxHeight, minPx),
    )
    val p = measurable.measure(inner)
    val w = p.width.coerceIn(constraints.minWidth, constraints.maxWidth)
    val h = p.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(w, h) { p.place((w - p.width) / 2, (h - p.height) / 2) }
}

/** 可聚焦的大卡片（scale 1.05；聚焦反相，与按钮一致；减少动画时不缩放）。 */
@Composable
fun TvCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val reduced = rememberReducedMotion()
    Surface(
        onClick = onClick,
        modifier = modifier.tvMinDrawSize().touchClickable(true, interaction, onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = if (reduced) ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f) else ClickableSurfaceDefaults.scale(focusedScale = 1.05f, pressedScale = 1.02f),
        interactionSource = interaction,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surfaceContainerHigh, contentColor = c.onSurface,
            focusedContainerColor = c.onSurface, focusedContentColor = c.inverseOnSurface,
            pressedContainerColor = c.onSurface, pressedContentColor = c.inverseOnSurface,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, c.primary), inset = (-3).dp, shape = shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(c.primary.copy(alpha = 0.32f), 6.dp)),
    ) {
        // 同 TvButton：让卡片内未指定颜色的 M3 Text / Icon 跟随聚焦反相
        CompositionLocalProvider(LocalContentColor provides TvLocalContentColor.current, content = content)
    }
}

/**
 * tv-material 的 Button / Surface 只响应遥控器按键（tvClickable = focusable + 按键 + 语义），不处理触摸。
 * 接收端也会跑在平板、手机等触屏设备上，这里补上点按手势，否则这些设备上的按钮点了没反应。
 * 按下时向组件的 [interactionSource] 发出 PressInteraction，复用 tv-material 自带的按下态（缩放 + 反相）作为触摸反馈；
 * tvClickable 只在按键抬起时触发 onClick，不监听这些交互，所以不会重复点击，也不影响遥控器行为。
 */
@Composable
private fun Modifier.touchClickable(enabled: Boolean, interactionSource: MutableInteractionSource, onClick: () -> Unit): Modifier {
    val current by rememberUpdatedState(onClick)
    return pointerInput(enabled, interactionSource) {
        if (enabled) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interactionSource.emit(press)
                    val released = tryAwaitRelease()
                    interactionSource.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onTap = { current() },
            )
        }
    }
}
