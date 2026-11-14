package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 独立格子的 PIN / 投屏码显示（交互稿 05A / 05B）。[groups] 例如 listOf(3, 5) 表示 3+5 分组。
 * [errorKey] 变化时抖动 1 次（120ms × 2）。
 */
@Composable
fun PinCells(
    value: String,
    length: Int,
    modifier: Modifier = Modifier,
    groups: List<Int> = listOf(length),
    error: Boolean = false,
    errorKey: Any? = null,
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
    Row(
        modifier
            .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
            .semantics { contentDescription = "已输入 ${value.length} 位，共 $length 位" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var index = 0
        groups.forEachIndexed { gi, size ->
            if (gi > 0) Spacer(Modifier.width(8.dp))
            repeat(size) {
                val i = index++
                val ch = value.getOrNull(i)
                val focused = i == value.length
                Box(
                    Modifier
                        .size(width = 44.dp, height = 56.dp)
                        .background(c.surfaceContainerLowest, RoundedCornerShape(12.dp))
                        .border(if (focused || error) 2.dp else 1.dp, if (error) c.error else if (focused) c.primary else c.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ch?.toString() ?: "", fontFamily = FontFamily.Monospace, fontSize = 26.sp, color = c.onSurface)
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
                    TextButton(
                        onClick = action,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            .background(if (key.length == 1 && key[0].isDigit()) c.surfaceContainerLowest else c.surfaceContainer, RoundedCornerShape(8.dp))
                            .semantics { if (key == "⌫") contentDescription = "删除" },
                    ) {
                        if (key == "⌫") {
                            Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = c.onSurface)
                        } else {
                            Text(key, style = if (key.length == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge, color = c.onSurface)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
