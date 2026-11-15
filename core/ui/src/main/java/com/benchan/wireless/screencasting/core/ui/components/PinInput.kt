package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** PIN 格子尺寸（交互稿 05A `.pin` / 05B `.pin.eight`）。 */
@Immutable
data class PinCellMetrics(
    val cellWidth: Dp,
    val cellHeight: Dp,
    val spacing: Dp,
    val fontSize: TextUnit,
    val cornerRadius: Dp,
    /** 分组分隔符“·”的字号。 */
    val separatorFontSize: TextUnit = 20.sp,
)

object PinCellsDefaults {
    /** 4 位 PIN：56×64、间距 12、28sp。 */
    val Pin = PinCellMetrics(cellWidth = 56.dp, cellHeight = 64.dp, spacing = 12.dp, fontSize = 28.sp, cornerRadius = 14.dp)

    /** 8 位投屏码：32×48、间距 5、20sp，3+5 分组中间“·”。 */
    val CastCode = PinCellMetrics(cellWidth = 32.dp, cellHeight = 48.dp, spacing = 5.dp, fontSize = 20.sp, cornerRadius = 10.dp)

    /** 分隔符占位宽度（与间距一起计算总宽）。 */
    internal val SeparatorWidth = 8.dp

    /** 一行格子的总宽度（不缩放时）。 */
    fun totalWidth(metrics: PinCellMetrics, length: Int, groups: List<Int>): Dp {
        val separators = (groups.size - 1).coerceAtLeast(0)
        val items = length + separators
        return metrics.cellWidth * length + SeparatorWidth * separators + metrics.spacing * (items - 1).coerceAtLeast(0)
    }

    /** 可用宽度不足时的等比缩放系数（≤ 1）。 */
    fun scaleFor(available: Dp, metrics: PinCellMetrics, length: Int, groups: List<Int>): Float {
        val total = totalWidth(metrics, length, groups)
        if (total.value <= 0f || available == Dp.Infinity) return 1f
        return (available.value / total.value).coerceIn(0.5f, 1f)
    }
}

/**
 * 独立格子的 PIN / 投屏码显示（交互稿 05A / 05B）。[groups] 例如 listOf(3, 5) 表示 3+5 分组，组间显示“·”。
 * 宽度不足时（小屏 / 横屏分栏）按比例缩小。[errorKey] 变化时抖动 1 次（120ms × 2）。
 *
 * @param secret true（4 位 PIN）时读屏只报告已输入位数；false（投屏码，不是秘密）时读出已输入的数字。
 */
@Composable
fun PinCells(
    value: String,
    length: Int,
    modifier: Modifier = Modifier,
    groups: List<Int> = listOf(length),
    error: Boolean = false,
    errorKey: Any? = null,
    metrics: PinCellMetrics = if (length <= 4) PinCellsDefaults.Pin else PinCellsDefaults.CastCode,
    secret: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    val shake = remember { Animatable(0f) }
    LaunchedEffect(errorKey) {
        if (errorKey != null && error) {
            repeat(2) {
                shake.animateTo(10f, tween(30))
                shake.animateTo(-10f, tween(60))
                shake.animateTo(0f, tween(30))
            }
        }
    }
    val description = if (secret || value.isEmpty()) {
        "已输入 ${value.length} 位，共 $length 位"
    } else {
        "已输入 ${value.toList().joinToString(" ")}，共 $length 位"
    }
    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val scale = PinCellsDefaults.scaleFor(maxWidth, metrics, length, groups)
        val density = LocalDensity.current
        val cellW = metrics.cellWidth * scale
        val cellH = metrics.cellHeight * scale
        val shape = RoundedCornerShape(metrics.cornerRadius * scale)
        // 格子尺寸固定，字号跟随系统字体缩放但不超过格子高度的 0.6，避免大字号时溢出
        val fontCap = with(density) { (cellH * 0.6f).toSp() }
        val font = (metrics.fontSize.value * scale).sp.let { if (it.value > fontCap.value) fontCap else it }
        Row(
            Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }.clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(metrics.spacing * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var index = 0
            groups.forEachIndexed { gi, size ->
                if (gi > 0) {
                    Text(
                        "·",
                        Modifier.width(PinCellsDefaults.SeparatorWidth * scale),
                        fontSize = metrics.separatorFontSize * scale,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                repeat(size) {
                    val i = index++
                    val ch = value.getOrNull(i)
                    val focused = i == value.length
                    Box(
                        Modifier
                            .size(width = cellW, height = cellH)
                            .background(c.surfaceContainerLowest, shape)
                            .border(if (focused || error) 2.dp else 1.dp, if (error) c.error else if (focused) c.primary else c.outline, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(ch?.toString() ?: "", fontFamily = FontFamily.Monospace, fontSize = font, color = c.onSurface)
                    }
                }
            }
        }
    }
}

/** 数字键盘（不调起系统输入法，避免 autofill 与 PIN 泄露到输入法；交互稿 05A：KeyboardType.NumberPassword 语义）。 */
@Composable
fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    digitsEnabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("取消", "0", "⌫"))
    Column(
        modifier.fillMaxWidth().background(c.surfaceContainer).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val action: () -> Unit = when (key) {
                        "取消" -> onCancel
                        "⌫" -> onBackspace
                        else -> { { onDigit(key[0]) } }
                    }
                    val isDigit = key.length == 1 && key[0].isDigit()
                    TextButton(
                        onClick = action,
                        enabled = !isDigit || digitsEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            .background(if (key.length == 1 && key[0].isDigit()) c.surfaceContainerLowest else c.surfaceContainer, RoundedCornerShape(8.dp))
                            .semantics { if (key == "⌫") contentDescription = "删除" },
                    ) {
                        if (key == "⌫") {
                            Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = c.onSurface)
                        } else {
                            Text(
                                key,
                                style = if (key.length == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                                color = if (!isDigit || digitsEnabled) c.onSurface else c.outline, // 不可用用 outline，不叠透明度
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
