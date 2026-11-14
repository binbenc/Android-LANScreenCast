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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.benchan.wireless.screencasting.core.ui.theme.ScrimAlpha
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
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
        CastProtocol.WSC -> "屏幕镜像"
        CastProtocol.DLNA -> "视频推送（DLNA）"
        CastProtocol.AIRPLAY -> "兼容 Apple 设备"
    }
    ModalScrim {
        Column(
            Modifier.widthIn(max = 560.dp).background(c.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(32.dp)
                .trapFocus(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Smartphone, null, Modifier.size(32.dp), tint = c.primary)
            Text(
                if (request.replacing) "${request.peerName} 请求替换当前投屏" else "${request.peerName} 请求投屏",
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // “首次连接”用 warning 提醒注意来源（D2）
                if (request.firstTime) Text("首次连接", style = MaterialTheme.typography.bodyMedium, color = WscTheme.extended.warning)
                Text(
                    listOf(request.peerAddress, protocolLabel).joinToString(" · ", prefix = if (request.firstTime) "· " else ""),
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                )
            }
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
                // “允许”用 primary 填充表达主操作；默认焦点仍在“拒绝”
                TvButton("允许", { onRespond(true) }, emphasis = true, leading = { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp)) })
            }
            Text("$remaining 秒后自动拒绝", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

/**
 * 弹层遮罩（B5）：scrim ScrimAlpha.Text（60%），吃掉所有触摸（不会点到下面的待机页 / 播放页）。
 */
@Composable
internal fun ModalScrim(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha.Text))
            // 遮罩自身注册指针输入即成为命中目标，下层兄弟节点（待机页 / 播放页）不再收到触摸
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 把遥控器焦点限制在弹层内：方向键到边缘时不再移到下面的页面（B5）。 */
internal fun Modifier.trapFocus(): Modifier = this.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
