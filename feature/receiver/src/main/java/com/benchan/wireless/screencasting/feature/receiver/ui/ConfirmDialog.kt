package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.ui.components.TvButton
import com.benchan.wireless.screencasting.core.ui.theme.PinStyleTv
import com.benchan.wireless.screencasting.feature.receiver.session.ConfirmRequest
import kotlinx.coroutines.delay

/**
 * 交互稿 12：投屏请求确认 · PIN。默认焦点在“拒绝”（遥控器“确定”是高频习惯动作，误按的后果应可逆）；
 * 返回键 = 拒绝；30 秒倒计时与手机同步。“允许”= TV 端同意，会话仍需手机端 PIN 校验成功才建立。
 * DLNA 陌生设备推送沿用此弹窗（无 PIN 区域）。
 */
@Composable
internal fun ConfirmDialog(request: ConfirmRequest, onRespond: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val rejectFocus = remember { FocusRequester() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.id) {
        runCatching { rejectFocus.requestFocus() }
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    BackHandler { onRespond(false) }
    val remaining = ((request.deadlineAt - now) / 1000).coerceAtLeast(0)
    val protocolLabel = when (request.protocol) {
        CastProtocol.WSC -> "WSC 镜像"
        CastProtocol.DLNA -> "DLNA 推送"
        CastProtocol.AIRPLAY -> "兼容 Apple 设备"
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).background(c.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Smartphone, null, Modifier.size(32.dp), tint = c.primary)
            Text(
                if (request.replacing) "${request.peerName} 请求替换当前投屏" else "${request.peerName} 请求投屏",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                listOfNotNull(if (request.firstTime) "首次连接" else null, request.peerAddress, protocolLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
            request.pin?.let { pin ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "PIN ${pin.toList().joinToString(" ")}" },
                ) {
                    pin.forEach { ch ->
                        Box(Modifier.size(width = 64.dp, height = 76.dp).background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(ch.toString(), style = PinStyleTv)
                        }
                    }
                }
                Text("请在手机上输入此 PIN。配对后这台手机下次可直接投屏。", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TvButton("拒绝", { onRespond(false) }, Modifier.focusRequester(rejectFocus))
                TvButton("允许", { onRespond(true) }, leading = { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp)) })
            }
            Text("$remaining 秒后自动拒绝", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}
