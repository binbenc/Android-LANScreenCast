package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * TV 焦点态（交互稿 07 TV 焦点态规范）四重冗余编码：
 * 反相填充（onSurface 底 + inverseOnSurface 字）、缩放（按钮 1.08 / 卡片 1.05）、外环（3dp 间隙 + 2dp primary）、
 * 光晕（primary @ 32%，6dp）；按下 scale 1.02 去光晕保留外环；不可用 38%。
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.touchClickable(enabled, onClick).heightIn(min = 48.dp),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = CircleShape),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1.02f),
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
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    ) {
        leading?.invoke(this)
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 可聚焦的大卡片（scale 1.05）。 */
@Composable
fun TvCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.touchClickable(true, onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f, pressedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surfaceContainerHigh, contentColor = c.onSurface,
            focusedContainerColor = c.surfaceContainerHighest, focusedContentColor = c.onSurface,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, c.primary), inset = (-3).dp, shape = shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(c.primary.copy(alpha = 0.32f), 6.dp)),
    ) { content() }
}

/**
 * tv-material 的 Button / Surface 只响应遥控器按键（tvClickable = focusable + 按键 + 语义），不处理触摸。
 * 接收端也会跑在平板、手机等触屏设备上，这里补上点按手势，否则这些设备上的按钮点了没反应。
 */
@Composable
private fun Modifier.touchClickable(enabled: Boolean, onClick: () -> Unit): Modifier {
    val current by rememberUpdatedState(onClick)
    return pointerInput(enabled) {
        if (enabled) detectTapGestures(onTap = { current() })
    }
}
