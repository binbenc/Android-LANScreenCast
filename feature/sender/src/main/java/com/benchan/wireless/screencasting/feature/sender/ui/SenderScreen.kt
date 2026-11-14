package com.benchan.wireless.screencasting.feature.sender.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.feature.sender.service.ScreenCastService
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import com.benchan.wireless.screencasting.feature.sender.session.SenderEvent
import com.benchan.wireless.screencasting.feature.sender.session.SenderState

private enum class SubRoute { NONE, CAST_CODE, SCANNER }

/**
 * 发送端入口（交互稿 02~08）。由 [SenderController] 的会话状态驱动页面切换；
 * 列表上的“投屏码 / 扫码 / 手动 IP”是本页内的子路由。
 *
 * @param quickCast 从快捷设置图块进入：直连最近使用的设备（F12）。
 * @param deepLink 系统相机扫码打开的 wsc:// 链接。
 */
@Composable
fun SenderScreen(
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenInstallHelp: () -> Unit,
    quickCast: Boolean = false,
    deepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    viewModel: SenderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lan by viewModel.lan.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var sub by rememberSaveable { mutableStateOf(SubRoute.NONE) }
    var manualIp by rememberSaveable { mutableStateOf(false) }
    var introDismissed by remember { mutableStateOf(false) }

    // 发现：在列表页可见时运行（离开即释放 MulticastLock）
    val listVisible = sub == SubRoute.NONE && (state is SenderState.Idle || state is SenderState.Ended || state is SenderState.Connecting)
    DisposableEffect(listVisible) {
        if (listVisible) viewModel.startDiscovery() else viewModel.stopDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(quickCast) {
        if (quickCast && state is SenderState.Idle) {
            if (!viewModel.quickConnectRecent()) snackbar.showSnackbar("还没有最近使用的设备，请先从列表选择")
        }
    }
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            if (!viewModel.connectQr(deepLink)) snackbar.showSnackbar("无法识别的连接链接")
            onDeepLinkConsumed()
        }
    }

    // —— 权限与录屏授权 ——
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 授权结果只能用一次：立即交给 mediaProjection 前台服务（PRD S-01）
            ScreenCastService.start(context, result.resultCode, result.data!!)
        } else {
            viewModel.onCaptureDenied()
        }
    }
    fun requestProjection() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }
    fun startCaptureFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // 第一次投屏时申请通知权限（前台服务通知和停止按钮，PRD 8.5）；拒绝也继续
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setAudioPreference(granted)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sub = SubRoute.SCANNER
    }
    fun openScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            sub = SubRoute.SCANNER
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // —— 一次性事件 → Snackbar ——
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is SenderEvent.AutoDowngraded -> {
                    val r = snackbar.showSnackbar("网络较差，已切换为“流畅”", actionLabel = "撤销", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoAutoDowngrade()
                }
                SenderEvent.Recovered -> snackbar.showSnackbar("已恢复")
                is SenderEvent.PinIncorrect -> Unit
            }
        }
    }
    LaunchedEffect(state) {
        val s = state
        when (s) {
            is SenderState.ReadyToCapture -> {
                introDismissed = false
                sub = SubRoute.NONE
            }
            is SenderState.Connecting -> sub = SubRoute.NONE
            is SenderState.Ended -> {
                val text = s.reason.snackbarText(s.target?.name)
                if (text != null) {
                    // acknowledge() 会把状态切回 Idle 并重启本 LaunchedEffect；Snackbar 必须在不随 state 取消的作用域里显示
                    snackbarScope.launch { snackbar.showSnackbar(s.detail ?: text) }
                    viewModel.acknowledge()
                }
            }
            else -> Unit
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is SenderState.AwaitingPin -> PinEntryScreen(s, viewModel::submitPin, viewModel::cancel)
                is SenderState.AwaitingTvConfirm -> WaitingScreen("等待 ${s.target.name} 确认…", "请在电视上选择“允许”", s.deadlineAt, viewModel::cancel)
                is SenderState.Starting -> WaitingScreen("正在建立连接…", "首帧通常在 2 秒内出现", null, viewModel::stop)
                is SenderState.Casting -> {
                    BackHandler(enabled = false) {}
                    CastingScreen(s, viewModel::setQuality, viewModel::setAutoAdjust, viewModel::setAudioEnabled, viewModel::stop)
                }
                is SenderState.Reconnecting -> ReconnectingScreen(s, viewModel::stop)
                is SenderState.Ended -> if (s.reason in setOf(EndReason.RECONNECT_FAILED, EndReason.SCREEN_LOCKED, EndReason.PROJECTION_STOPPED, EndReason.CAPTURE_DENIED, EndReason.VERSION_UNSUPPORTED)) {
                    EndedScreen(
                        s,
                        onReconnect = {
                            // 重连失败后“重新连接”跳过说明卡，直达系统授权（交互稿 Q11）
                            val t = s.target
                            viewModel.acknowledge()
                            if (t != null) {
                                viewModel.setSkipIntroOnce()
                                viewModelConnect(viewModel, t)
                            }
                        },
                        onBack = viewModel::acknowledge,
                        onDiagnostics = { viewModel.acknowledge(); onOpenDiagnostics() },
                    )
                } else {
                    ListContent(viewModel, devices, sub, ::openScanner, { sub = SubRoute.CAST_CODE }, { manualIp = true }, onOpenSettings, onOpenDiagnostics, onOpenInstallHelp)
                }
                else -> when (sub) {
                    SubRoute.CAST_CODE -> CastCodeScreen(lan, viewModel::connectCastCode, { manualIp = true }, { sub = SubRoute.NONE })
                    SubRoute.SCANNER -> QrScannerScreen(onResult = { text -> viewModel.connectQr(text).also { if (it) sub = SubRoute.NONE } }, onBack = { sub = SubRoute.NONE })
                    SubRoute.NONE -> ListContent(viewModel, devices, sub, ::openScanner, { sub = SubRoute.CAST_CODE }, { manualIp = true }, onOpenSettings, onOpenDiagnostics, onOpenInstallHelp)
                }
            }
            if (state is SenderState.Connecting) {
                WaitingScreen("正在连接 ${(state as SenderState.Connecting).target.name}…", "加密通道建立中", null, viewModel::cancel)
            }
        }
    }

    val ready = state as? SenderState.ReadyToCapture
    // 说明卡“下次不再提示”，或重连失败后的“重新连接”（交互稿 Q11）：跳过说明卡直达系统授权
    val skipIntro = remember(ready) { ready != null && (settings?.skipCaptureIntro == true || viewModel.consumeSkipIntroOnce(peek = false)) }
    LaunchedEffect(ready) {
        if (ready != null && skipIntro) {
            introDismissed = true
            startCaptureFlow()
        }
    }
    if (ready != null && !introDismissed && !skipIntro) {
        CaptureIntroSheet(
            targetName = ready.target.name,
            audioEnabled = settings?.audioEnabled ?: true,
            onAudioChange = { on ->
                if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    // 首次开启声音时才申请（说明卡上已写明用途）
                    audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    viewModel.setAudioPreference(on)
                }
            },
            onContinue = { skip ->
                introDismissed = true
                if (skip) viewModel.setSkipIntro(true)
                startCaptureFlow()
            },
            onCancel = {
                introDismissed = true
                viewModel.cancel()
            },
        )
    }

    if (manualIp) {
        ManualIpDialog(onDismiss = { manualIp = false }) { text -> viewModel.connectManual(text).also { if (it) manualIp = false } }
    }
}

private fun viewModelConnect(vm: SenderViewModel, t: com.benchan.wireless.screencasting.feature.sender.session.CastTarget) = vm.reconnectTo(t)

@Composable
private fun ListContent(
    viewModel: SenderViewModel,
    devices: DeviceListUi,
    @Suppress("UNUSED_PARAMETER") sub: SubRoute,
    onScan: () -> Unit,
    onCastCode: () -> Unit,
    onManualIp: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onInstallHelp: () -> Unit,
) {
    DeviceListScreen(
        ui = devices,
        searchStartedAt = viewModel.searchStartedAt,
        onDevice = viewModel::connect,
        onForget = { viewModel.forgetDevice(it.device.deviceId) },
        onScan = onScan,
        onCastCode = onCastCode,
        onManualIp = onManualIp,
        onSettings = onSettings,
        onDiagnostics = onDiagnostics,
        onInstallHelp = onInstallHelp,
    )
}
