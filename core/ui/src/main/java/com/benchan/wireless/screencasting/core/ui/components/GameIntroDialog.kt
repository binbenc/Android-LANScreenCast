package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 交互稿 15 G01：第一次选“游戏”的一次性说明卡（本机一生一次）。三行“得到什么 / 失去什么 / 要注意什么”，
 * 图标用中性色（知情说明，不是警告）；不写具体延迟承诺。点“取消”/ 点遮罩 = 档位不变。
 * 发送端投屏中页（07）与设置默认画质（09A / G06）共用。
 */
@Composable
fun GameIntroDialog(onResult: (enable: Boolean) -> Unit) {
    val isTv = LocalIsTv.current
    val titleFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    // 手机：焦点在标题（读屏先念标题）；TV：标题没有焦点样式，默认焦点给“取消”（UX 走查 P2-15）
    LaunchedEffect(Unit) { runCatching { if (isTv) cancelFocus.requestFocus() else titleFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { onResult(false) },
        icon = { Icon(Icons.Rounded.SportsEsports, null, tint = MaterialTheme.colorScheme.secondary) },
        title = {
            Text(
                "开启游戏模式？",
                if (isTv) Modifier.semantics { heading() } else Modifier.semantics { heading() }.focusRequester(titleFocus).focusable(),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                IntroRow(Icons.Rounded.Speed, "延迟更低，画面更流畅", "清晰度降到 720p，每秒 60 帧；网络不稳时画面可能跳一下")
                IntroRow(Icons.AutoMirrored.Rounded.VolumeUp, "声音可能比画面稍晚", "看视频建议用“均衡”")
                IntroRow(Icons.Rounded.Thermostat, "手机发热和耗电会增加", "长时间玩，建议接上充电器")
            }
        },
        // “开启”在“取消”之后（读屏顺序，交互稿 G01）
        dismissButton = { TextButton(onClick = { onResult(false) }, modifier = Modifier.focusRequester(cancelFocus).tvFocusRing()) { Text("取消") } },
        confirmButton = { TextButton(onClick = { onResult(true) }, modifier = Modifier.tvFocusRing()) { Text("开启") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun IntroRow(icon: ImageVector, title: String, detail: String) {
    // 每行一个读屏焦点：“延迟更低，画面更流畅，清晰度降到 720p，每秒 60 帧”
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

