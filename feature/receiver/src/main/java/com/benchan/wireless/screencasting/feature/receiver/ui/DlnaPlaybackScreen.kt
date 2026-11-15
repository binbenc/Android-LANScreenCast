package com.benchan.wireless.screencasting.feature.receiver.ui

import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val imageUri = playback.imageUri
        if (imageUri != null) {
            // 照片推送（第 1 轮 EMU-2）：直接显示图片，切换照片时随地址更新
            PushedImage(imageUri, playback.title)
        } else if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        keepScreenOn = true
                        this.player = player
                        // 始终完整显示（不裁切），HEVC / 带像素宽高比的片源也一样（第 1 轮 EMU-6）
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
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
            listOfNotNull(playback.title, (if (playback.imageUri != null) "照片推送" else "视频推送") + " · 来自 ${playback.controlPoint}").joinToString("  ·  "),
            tag,
            Modifier.align(Alignment.TopStart),
        )
    }
}

/** 推送的图片：按屏幕尺寸采样解码，避免 5000 万像素原图撑爆内存。地址来自控制点推送（PRD 8.5 例外）。 */
@Composable
private fun PushedImage(uri: String, title: String?) {
    val context = LocalContext.current
    val maxSide = remember { context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceIn(1280, 3840) }
    val state by produceState<Any?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadBitmap(uri, maxSide) }.getOrNull() ?: false }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val v = state) {
            null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.onScrim)
            is Bitmap -> Image(v.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> Text("这张照片无法显示", color = MaterialTheme.colorScheme.onScrim, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun loadBitmap(uri: String, maxSide: Int): Bitmap? {
    fun open(): java.io.InputStream {
        val c = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        return c.inputStream
    }
    // 先读尺寸再按 2 的幂采样（读两次：电视端照片一般 ≤ 4 MB）
    val bytes = open().use { it.readBytes().takeIf { b -> b.size <= 64 * 1024 * 1024 } } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
