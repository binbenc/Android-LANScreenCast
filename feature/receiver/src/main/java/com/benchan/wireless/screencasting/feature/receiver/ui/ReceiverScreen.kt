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
    // 每个实例最多持有一次监听引用：以前 ON_STOP 和 onDispose 都会 stopListening，页面切换动画结束时旧实例被移除，
    // 多减一次把新实例的引用也抵消了，监听在待机页可见时被停掉（E8）
    DisposableEffect(lifecycle) {
        var holding = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!holding) {
                    holding = true
                    viewModel.startListening()
                }
                Lifecycle.Event.ON_STOP -> if (holding) {
                    holding = false
                    viewModel.stopListening()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (holding) {
                holding = false
                viewModel.stopListening()
            }
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
    LaunchedEffect(lastInput, session, request, dlna, apple) {
        saver = false
        if (session == null && request == null && dlna == null && apple !is AppleCompatState.Active) {
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
            apple is AppleCompatState.Active ->
                AppleCompatPlayback((apple as AppleCompatState.Active).session, viewModel.appleVideoPlayer, viewModel::setAppleSurface, viewModel::disconnectApple, viewModel::setVideoDisplay)
            dlna != null -> DlnaPlaybackScreen(dlna!!, viewModel.dlnaPlayer, viewModel::stopDlna, viewModel::setVideoDisplay)
            s != null -> PlaybackScreen(s, viewModel.eglContext, viewModel::attach, viewModel::detach, viewModel::disconnect)
            saver -> ScreenSaver(standby)
            else -> StandbyScreen(standby, onOpenSettings, onEnableAppleCompat, appleFailure = (apple as? AppleCompatState.Failed)?.reason)
        }
        // 确认弹窗与 Apple PIN 覆盖层在 Activity 根部渲染（ReceiverModalHost），任何页面都能看到（P1）
    }
}

private const val SCREEN_SAVER_MS = 10 * 60 * 1000L
