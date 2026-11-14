package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleCompatState

/** 兼容 Apple 设备镜像画面（MediaCodec 直接解码到这个 SurfaceView）。 */
@Composable
private fun AppleMirrorSurface(setSurface: (android.view.Surface?) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                keepScreenOn = true
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) = setSurface(h.surface)
                    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) = Unit
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) = setSurface(null)
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 接收端入口（交互稿 11~15）。待机监听只在本页可见（App 前台）时开启（PRD R-05）。
 */
@Composable
fun ReceiverScreen(
    onOpenSettings: () -> Unit,
    onEnableAppleCompat: () -> Unit,
    extraPlayback: (@Composable () -> Boolean)? = null,
    viewModel: ReceiverViewModel = hiltViewModel(),
) {
    val standby by viewModel.standby.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val request by viewModel.request.collectAsStateWithLifecycle()
    val dlna by viewModel.dlnaPlayback.collectAsStateWithLifecycle()
    val apple by viewModel.appleState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> viewModel.startListening()
                Lifecycle.Event.ON_STOP -> viewModel.stopListening()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            viewModel.stopListening()
        }
    }
    LaunchedEffect(standby.notice) {
        if (standby.notice != null) {
            delay(8_000)
            viewModel.clearNotice()
        }
    }
    // 10 分钟无操作进入屏保（R-01）
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var saver by remember { mutableStateOf(false) }
    LaunchedEffect(lastInput, session, request, dlna) {
        saver = false
        if (session == null && request == null && dlna == null) {
            delay(SCREEN_SAVER_MS)
            saver = true
        }
    }

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent {
                lastInput = System.currentTimeMillis()
                if (saver) {
                    saver = false
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        lastInput = System.currentTimeMillis()
                    }
                }
            },
    ) {
        val s = session
        val handledByExtra = extraPlayback?.invoke() == true
        when {
            handledByExtra -> Unit
            apple is AppleCompatState.Mirroring -> AppleMirrorSurface(viewModel::setAppleSurface)
            dlna != null -> DlnaPlaybackScreen(dlna!!, viewModel.dlnaPlayer, viewModel::stopDlna)
            s != null -> PlaybackScreen(s, viewModel.eglContext, viewModel::attach, viewModel::detach, viewModel::disconnect)
            saver -> ScreenSaver(standby)
            else -> StandbyScreen(standby, onOpenSettings, onEnableAppleCompat, applePin = (apple as? AppleCompatState.Advertising)?.pin)
        }
        request?.let { r -> ConfirmDialog(r) { allowed -> viewModel.respond(r.id, allowed) } }
    }
}

private const val SCREEN_SAVER_MS = 10 * 60 * 1000L
