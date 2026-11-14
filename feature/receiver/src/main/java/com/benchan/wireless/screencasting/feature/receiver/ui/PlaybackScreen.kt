package com.benchan.wireless.screencasting.feature.receiver.ui

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppsOutage
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.ui.components.LatencySparkline
import com.benchan.wireless.screencasting.core.ui.components.TvButton
import com.benchan.wireless.screencasting.core.ui.components.formatMbps
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.receiver.session.MirrorSession
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import java.util.Locale

/**
 * 交互稿 13（镜像播放中 · 菜单键统计浮层）/ 14（重连中 · 保留最后一帧）/ 15（受保护内容提示）。
 * 播放画面用 AndroidView 承载 SurfaceViewRenderer（等比 + 黑边），Compose 层只放浮层，避免整树重组。
 */
@Composable
internal fun PlaybackScreen(
    session: MirrorSession,
    eglContext: EglBase.Context,
    attach: (VideoSink) -> Unit,
    detach: (VideoSink) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hint by remember { mutableStateOf(true) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var protectedDismissed by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(session.firstFrame) {
        if (session.firstFrame) {
            hint = true
            delay(3_000)
            hint = false
        }
    }
    LaunchedEffect(overlay, lastInteraction) {
        if (overlay) {
            delay(8_000)
            overlay = false
        }
    }
    LaunchedEffect(session.protectedSuspected) { if (!session.protectedSuspected) protectedDismissed = false }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // 返回键 1 次 → 提示；2 秒内第 2 次 → 断开（PRD 6.2：连按两次返回才断开）
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2_000) {
            onDisconnect()
        } else {
            lastBack = now
            Toast.makeText(context, "再按一次返回键断开投屏", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && e.key.nativeKeyCode == KeyEvent.KEYCODE_MENU) {
                    overlay = !overlay
                    lastInteraction = System.currentTimeMillis()
                    true
                } else {
                    if (overlay) lastInteraction = System.currentTimeMillis()
                    false
                }
            }
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                overlay = !overlay
                lastInteraction = System.currentTimeMillis()
            },
    ) {
        VideoSurface(eglContext, attach, detach)

        // 14：重连中，最后一帧降亮度到 50% + 居中胶囊
        if (session.reconnecting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = WscTheme.extended.warning)
                        Text("正在重连…", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("${session.peerName} · 第 ${session.reconnectAttempt.coerceAtLeast(1)} 次 · 最长等待 30 秒", style = MaterialTheme.typography.bodyMedium)
                }
                Text("画面为断开前的最后一帧", Modifier.align(Alignment.BottomCenter).padding(32.dp), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        } else if (!session.firstFrame) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("正在接收 ${session.peerName} 的画面…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // S-02：单应用共享且该应用退到后台 → 占位画面而不是黑屏
        if (!session.appVisible && !session.reconnecting) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.AppsOutage, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("发送端已切换应用", style = MaterialTheme.typography.titleLarge)
                    Text("回到被共享的应用后画面会自动恢复", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 15：受保护内容提示（推断，交互稿 Q3 / Q12）
        if (session.protectedSuspected && !protectedDismissed && !session.reconnecting) {
            ProtectedCard(Modifier.align(Alignment.Center)) { protectedDismissed = true }
        }

        // 录音权限被拒 → “无声音”角标
        if (session.audio == AudioState.NO_PERMISSION || session.audio == AudioState.OFF) {
            Row(
                Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 27.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.VolumeOff, null, Modifier.size(16.dp), tint = Color.White)
                Text("无声音", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        AnimatedVisibility(hint && session.firstFrame && !overlay, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
            Text(
                "按菜单键查看连接信息 · 连按两次返回断开",
                Modifier.padding(horizontal = 48.dp, vertical = 27.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White, style = MaterialTheme.typography.bodySmall,
            )
        }

        AnimatedVisibility(overlay, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
            StatsOverlay(session, onDisconnect = onDisconnect, onClose = { overlay = false; runCatching { rootFocus.requestFocus() } })
        }
    }
}

@Composable
private fun VideoSurface(eglContext: EglBase.Context, attach: (VideoSink) -> Unit, detach: (VideoSink) -> Unit) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            // 只在投屏中保持屏幕常亮（PRD 9.4）
            keepScreenOn = true
        }
    }
    DisposableEffect(renderer) {
        attach(renderer)
        onDispose {
            detach(renderer)
            renderer.release()
        }
    }
    AndroidView({ renderer }, Modifier.fillMaxSize())
}

@Composable
private fun StatsOverlay(session: MirrorSession, onDisconnect: () -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val s = session.stats
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    Column(
        Modifier.padding(horizontal = 48.dp, vertical = 27.dp).widthIn(max = 360.dp)
            .background(c.surfaceContainerHigh.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("投屏统计", style = MaterialTheme.typography.titleMedium)
            Text("菜单键收起", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        StatRow("来源", session.peerName)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("协议", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = c.tertiary)
                Text("WSC · 已加密", style = MaterialTheme.typography.bodyMedium)
            }
        }
        StatRow("分辨率", if (s.width > 0) "${s.width} × ${s.height} · ${s.fps} fps" else "—")
        StatRow("码率", if (s.bitrateKbps > 0) formatMbps(s.bitrateKbps) else "—")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("端到端延迟（估算）", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Text(
                (s.latencyMs?.let { "$it ms " } ?: "— ") + (s.grade?.label ?: ""),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = ext.latency(s.grade),
            )
        }
        StatRow("丢帧", String.format(Locale.US, "%.1f %%", s.dropPercent))
        StatRow("解码", listOfNotNull(s.hardwareDecoder?.let { if (it) "硬件" else "软件" }, s.codec ?: s.decoder).joinToString(" · ").ifBlank { "—" })
        LatencySparkline(s.latencyHistory, Modifier.fillMaxWidth().height(56.dp))
        Text("最近 60 秒 · 虚线 = 100 ms", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("断开投屏", onDisconnect)
            TvButton("关闭", onClose, Modifier.focusRequester(closeFocus))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
    }
}

/** 交互稿 15：先排除“故障”归因，再给替代路径；不出现任何“绕过”字样。 */
@Composable
private fun ProtectedCard(modifier: Modifier, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.widthIn(max = 560.dp).background(c.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Shield, null, Modifier.size(36.dp), tint = c.tertiary)
        Text("该内容可能受版权保护，无法镜像", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text("这是视频应用的保护设置，不是连接故障。\n投屏仍在进行，手机切换到其他画面即可恢复。", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        Column(Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("建议改用推送", style = MaterialTheme.typography.titleMedium)
            Text("在视频 App 里点「投屏」按钮，选择本机，原画质播放、手机可锁屏", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
        TvButton("知道了", onDismiss, Modifier.focusRequester(focus))
        Box(Modifier.width(1.dp))
    }
}
