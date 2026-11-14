package com.benchan.wireless.screencasting.core.ui.components

import android.provider.Settings
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 当前是否运行在 TV（遥控器 + 过扫描）上。由 [com.benchan.wireless.screencasting.core.ui.theme.WscAppTheme] 提供，
 * 手机 / 平板上为 false，下面的 TV 修饰符全部是空操作。
 */
val LocalIsTv = staticCompositionLocalOf { false }

/**
 * 根部弹层（接收端屏 12 确认弹窗、Apple PIN 覆盖层）是否正在显示。由接收端的 ReceiverModalHost 提供；
 * 各页面据此在弹层期间不抢焦点、收起自己的对话框、禁用返回键处理，弹层关闭后再恢复焦点（U2 / S-a / S-d）。
 */
val LocalModalOverlayVisible = compositionLocalOf { false }

/** TV 过扫描安全区（5%）：水平 48dp、垂直 27dp（交互稿 11）。 */
object TvSafeArea {
    val Horizontal = 48.dp
    val Vertical = 27.dp
}

/** 系统“移除动画”（开发者选项 / 无障碍）打开时返回 true：焦点不再缩放（交互稿 07 动效规范）。 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** TV 上加过扫描安全区内边距；其他设备不变。 */
fun Modifier.tvSafeArea(): Modifier = composed {
    if (LocalIsTv.current) padding(horizontal = TvSafeArea.Horizontal, vertical = TvSafeArea.Vertical) else this
}

/**
 * TV 上进入页面时让该元素获得焦点（放在可聚焦组件的 modifier 最前面）。[key] 变化时重新请求；
 * 根部弹层显示期间不请求，弹层关闭后自动重新请求（焦点回到该元素）。
 */
fun Modifier.tvInitialFocus(key: Any? = Unit): Modifier = composed {
    val isTv = LocalIsTv.current
    val modal = LocalModalOverlayVisible.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(isTv, key, modal) {
        if (isTv && !modal) runCatching { requester.requestFocus() }
    }
    focusRequester(requester)
}

/**
 * M3 按钮（Button / TextButton / IconButton / SegmentedButton）在 TV 上的焦点样式：
 * 3dp 间隙 + 2dp primary 外环 + 轻微放大（减少动画时不放大），与 tv-material 组件的焦点外环一致，对比度 ≥ 3:1。
 * 放在按钮 modifier 的最前面，以便观察到按钮内部 focusable 的焦点。
 */
fun Modifier.tvFocusRing(cornerRadius: androidx.compose.ui.unit.Dp = 24.dp): Modifier = composed {
    if (!LocalIsTv.current) return@composed this
    val ring = MaterialTheme.colorScheme.primary
    val reduced = rememberReducedMotion()
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            val s = if (focused && !reduced) 1.04f else 1f
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val gap = 3.dp.toPx()
                val stroke = 2.dp.toPx()
                val r = cornerRadius.toPx() + gap
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-gap - stroke / 2, -gap - stroke / 2),
                    size = Size(size.width + 2 * gap + stroke, size.height + 2 * gap + stroke),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(stroke),
                )
            }
        }
}

/**
 * TV 上 `clickable` / `toggleable` / `selectable` 行的默认 Indication（替换手机上的水波纹）：
 * 聚焦时叠一层 onSurface 12% 并画 2dp primary 内描边（行通常铺满宽度，外环会被裁掉），按下时再加深。
 */
class TvFocusIndication(
    private val ring: Color,
    private val overlay: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, ring, overlay)

    override fun equals(other: Any?): Boolean = other is TvFocusIndication && other.ring == ring && other.overlay == overlay
    override fun hashCode(): Int = ring.hashCode() * 31 + overlay.hashCode()

    private class Node(
        private val source: InteractionSource,
        private val ring: Color,
        private val overlay: Color,
    ) : Modifier.Node(), DrawModifierNode {
        private var focusCount = 0
        private var pressCount = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is FocusInteraction.Focus -> focusCount++
                        is FocusInteraction.Unfocus -> focusCount = (focusCount - 1).coerceAtLeast(0)
                        is PressInteraction.Press -> pressCount++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = (pressCount - 1).coerceAtLeast(0)
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val focused = focusCount > 0
            if (focused || pressCount > 0) {
                drawRect(overlay.copy(alpha = if (pressCount > 0) 0.20f else 0.12f))
            }
            if (focused) {
                val stroke = 2.dp.toPx()
                val r = 12.dp.toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(stroke),
                )
            }
        }
    }
}
