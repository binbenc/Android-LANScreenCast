package com.benchan.wireless.screencasting.feature.receiver.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.benchan.wireless.screencasting.core.ui.components.LocalModalOverlayVisible
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.benchan.wireless.screencasting.core.ui.theme.onScrim
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
internal fun DlnaPlaybackScreen(playback: DlnaPlayback, player: Player?, onStop: () -> Unit, onDisplay: (Boolean) -> Unit = {}) {
    // 告诉控制器“视频画面已挂上”（后台开始的视频 10 秒无画面判定，R1）
    DisposableEffect(Unit) {
        onDisplay(true)
        onDispose { onDisplay(false) }
    }
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    // 根部弹层可见时把返回键让给弹层（S-d）
    BackHandler(enabled = !LocalModalOverlayVisible.current) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2_000) onStop() else {
            lastBack = now
            Toast.makeText(context, "再按一次返回键停止播放", Toast.LENGTH_SHORT).show()
        }
    }
    ImmersiveSystemBars()
    val tag = rememberSourceTagState()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).showSourceTagOnKeys(tag)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        keepScreenOn = true
                        this.player = player
                        // 控制条出现（遥控器按键或触摸）时同时显示来源标签（S-b）
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> if (v == android.view.View.VISIBLE) tag.show() })
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        FadingSourceTag(
            listOfNotNull(playback.title, "视频推送 · 来自 ${playback.controlPoint}").joinToString("  ·  "),
            tag,
            Modifier.align(Alignment.TopStart),
        )
    }
}
