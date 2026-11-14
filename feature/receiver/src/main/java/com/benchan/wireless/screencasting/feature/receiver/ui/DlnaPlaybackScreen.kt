package com.benchan.wireless.screencasting.feature.receiver.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.benchan.wireless.screencasting.protocol.dlna.DlnaPlayback

/**
 * DLNA 推送播放页（F09）：Media3 PlayerView（SurfaceView），遥控器可暂停、拖动进度；连按两次返回停止。
 */
@OptIn(UnstableApi::class)
@Composable
internal fun DlnaPlaybackScreen(playback: DlnaPlayback, player: Player?, onStop: () -> Unit) {
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2_000) onStop() else {
            lastBack = now
            Toast.makeText(context, "再按一次返回键停止播放", Toast.LENGTH_SHORT).show()
        }
    }
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
        }
        Text(
            listOfNotNull(playback.title, "DLNA 推送 · 来自 ${playback.controlPoint}").joinToString("  ·  "),
            Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 27.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White, style = MaterialTheme.typography.bodySmall,
        )
    }
}
