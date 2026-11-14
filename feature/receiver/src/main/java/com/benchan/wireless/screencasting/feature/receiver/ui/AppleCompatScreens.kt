package com.benchan.wireless.screencasting.feature.receiver.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.benchan.wireless.screencasting.core.ui.theme.PinStyleTv
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleSession
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleSessionKind

/*
 * 兼容 Apple 设备（非官方）播放页。界面文案不出现 “AirPlay” 字样与苹果图标（PRD 12、决策 #23）。
 */

private const val SOURCE_LABEL = "兼容 Apple 设备"

/** 连按两次返回断开（与 WSC 播放页一致，PRD 6.2）。 */
@Composable
private fun DoubleBackToDisconnect(message: String, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2_000) {
            onDisconnect()
        } else {
            lastBack = now
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun SourceTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 48.dp, vertical = 27.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color.White, style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun AppleCompatPlayback(
    session: AppleSession,
    videoPlayer: Player?,
    setSurface: (android.view.Surface?) -> Unit,
    onDisconnect: () -> Unit,
) {
    DoubleBackToDisconnect("再按一次返回键断开", onDisconnect)
    when (session.kind) {
        AppleSessionKind.MIRROR -> AppleMirror(session, setSurface)
        AppleSessionKind.VIDEO_URL -> AppleVideoUrl(session, videoPlayer)
        AppleSessionKind.AUDIO -> AppleAudio(session)
    }
}

/** 镜像：MediaCodec 直接解码到这个 SurfaceView。 */
@Composable
private fun AppleMirror(session: AppleSession, setSurface: (android.view.Surface?) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = setSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) = setSurface(null)
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (session.firstFrameAt == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text("正在连接 ${session.peerName}…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        SourceTag("$SOURCE_LABEL · ${session.peerName}", Modifier.align(Alignment.TopStart))
    }
}

/** 视频网址：Media3 PlayerView（SurfaceView），遥控器可暂停 / 拖动。 */
@OptIn(UnstableApi::class)
@Composable
private fun AppleVideoUrl(session: AppleSession, player: Player?) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        keepScreenOn = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        SourceTag("视频网址 · $SOURCE_LABEL · ${session.peerName}", Modifier.align(Alignment.TopStart))
    }
}

/** 仅音频：显示曲目信息（不显示封面）。 */
@Composable
private fun AppleAudio(session: AppleSession) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(96.dp), tint = c.primary)
            Text(session.trackTitle ?: "正在播放音频", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
            session.trackArtist?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant) }
            Text("来自 ${session.peerName} · $SOURCE_LABEL", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}

/**
 * Apple 设备首次连接时的配对 PIN（PIN 默认开启，决策 #23）：覆盖在任何页面之上，方便在 iPhone 上照着输入。
 */
@Composable
internal fun AppleCompatPinOverlay(pin: String) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).background(c.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Apple 设备请求连接", style = MaterialTheme.typography.headlineSmall)
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
            Text("请在 iPhone / iPad 上输入此 PIN。配对后这台设备下次可直接连接。", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}
