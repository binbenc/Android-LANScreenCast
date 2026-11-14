package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.discovery.qr.QrDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码连接（PRD 8.4、技术方案 3.6）：CameraX 预览 + ZXing core 解码 Y 平面，不使用 ML Kit / Play Services。
 * CAMERA 权限只在进入本页前申请。扫到 wsc:// 二维码即同时完成配对（免 PIN）。
 */
@Composable
internal fun QrScannerScreen(onResult: (String) -> Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hint by remember { mutableStateOf("对准电视待机页右侧的二维码") }
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (done.get()) return@setAnalyzer
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val text = QrDecoder.decodeY(bytes, image.width, image.height, plane.rowStride)
                            if (text != null && done.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(ctx).execute {
                                    if (!onResult(text)) {
                                        hint = "这不是本应用的连接二维码"
                                        done.set(false)
                                    }
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure {
                        WscLog.w("QrScanner", "bind camera failed", it)
                        hint = "无法打开相机，请改用投屏码"
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
        Box(Modifier.align(Alignment.Center).size(240.dp).border(3.dp, Color.White, RoundedCornerShape(16.dp)))
        Column(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White) }
        }
        Text(
            hint,
            modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
}
