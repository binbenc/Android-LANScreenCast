package com.benchan.wireless.screencasting.feature.sender.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.ui.util.openAppDetailsSettings
import com.benchan.wireless.screencasting.core.ui.util.openAppNotificationSettings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.benchan.wireless.screencasting.feature.sender.service.ScreenCastService
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import com.benchan.wireless.screencasting.feature.sender.session.SenderEvent
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import kotlinx.coroutines.launch

private enum class SubRoute { NONE, CAST_CODE, SCANNER }

/** 用整页（而不是 Snackbar）说明的结束原因。 */
private val FULL_PAGE_END_REASONS = setOf(
    EndReason.RECONNECT_FAILED, EndReason.SCREEN_LOCKED, EndReason.PROJECTION_STOPPED, EndReason.CAPTURE_DENIED, EndReason.VERSION_UNSUPPORTED,
)

/**
 * 发送端入口（交互稿 02~08）。由 [SenderController] 的会话状态驱动页面切换；
 * 列表上的“投屏码 / 扫码 / 手动 IP”是本页内的子路由。
 *
 * 系统栏 inset：Scaffold 不再统一加内边距（contentWindowInsets = 0），由各页面自己处理——
 * 带 TopAppBar 的页面由 TopAppBar / 底栏处理，其余页面用 systemBarsPadding，扫码页预览铺满（C9）。
 *
 * @param quickCast 从快捷设置图块进入：直连最近使用的设备（F12）。一次性事件，处理后调用 [onQuickCastConsumed]。
 * @param deepLink 系统相机扫码打开的 wsc:// 链接。
 */
@Composable
fun SenderScreen(
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenInstallHelp: () -> Unit,
    quickCast: Boolean = false,
    onQuickCastConsumed: () -> Unit = {},
    deepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    viewModel: SenderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lan by viewModel.lan.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var sub by rememberSaveable { mutableStateOf(SubRoute.NONE) }
    var manualIp by rememberSaveable { mutableStateOf(false) }
    var introDismissed by remember { mutableStateOf(false) }
    // 跳过说明卡时，进入投屏后补一次“锁屏会停止投屏”提示（D6）
    var lockTipPending by remember { mutableStateOf(false) }

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // 发现：在列表页可见时运行（离开即释放 MulticastLock）
    val listVisible = sub == SubRoute.NONE && (state is SenderState.Idle || state is SenderState.Ended || state is SenderState.Connecting)
    DisposableEffect(listVisible) {
        if (listVisible) viewModel.startDiscovery() else viewModel.stopDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(quickCast) {
        if (!quickCast) return@LaunchedEffect
        val s = viewModel.state.value
        val ok = if (s is SenderState.Idle || s is SenderState.Ended) viewModel.quickConnectRecent() else true
        // 处理完立即复位（复位会重启本 effect，所以 Snackbar 放到独立作用域），保证每次点图块都能再次触发（B3）
        onQuickCastConsumed()
        if (!ok) snackbarScope.launch { snackbar.showSnackbar("还没有最近使用的设备，请先从列表选择") }
    }
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            val ok = viewModel.connectQr(deepLink)
            onDeepLinkConsumed()
            if (!ok) snackbarScope.launch { snackbar.showSnackbar("无法识别的连接链接") }
        }
    }

    // —— 权限与录屏授权：录音（可选）→ 通知（可选）→ 系统录屏授权 ——
    // 通知权限不在投屏流程里弹出：屏 07 放一张可关闭的提示卡，用户点按钮再申请（S-c）。
    // 未授予时前台服务照常运行（startForeground 不需要通知权限），只是通知栏不显示“停止 / 切换为流畅”。
    var notificationGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) }
    var notificationTipDismissed by rememberSaveable { mutableStateOf(false) }
    // 申请过且系统不再弹窗（永久拒绝）时，按钮改为“去系统设置”
    var notificationPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
        if (!granted && Build.VERSION.SDK_INT >= 33) {
            notificationPermanentlyDenied = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        }
    }
    // 从系统设置返回时重新检查，授权后提示卡消失
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                notificationGranted = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
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
    fun continueAfterAudio() = requestProjection()
    // 声音偏好为开（默认）但还没有录音权限：录屏授权前先申请；拒绝不阻断，只投画面（A2）
    val captureAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { continueAfterAudio() }
    fun startCaptureFlow() {
        val wantsAudio = settings?.audioEnabled ?: true
        if (wantsAudio && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            captureAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            continueAfterAudio()
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setAudioPreference(granted)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            sub = SubRoute.SCANNER
        } else {
            // 拒绝后给出原因和替代路径；永久拒绝（不再弹窗）时提供“去系统设置”（C7）
            val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
            snackbarScope.launch {
                if (canAskAgain) {
                    val r = snackbar.showSnackbar(SenderCopy.CAMERA_DENIED, actionLabel = SenderCopy.CAMERA_DENIED_ACTION, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) sub = SubRoute.CAST_CODE
                } else {
                    val r = snackbar.showSnackbar(SenderCopy.CAMERA_PERMANENTLY_DENIED, actionLabel = SenderCopy.OPEN_SETTINGS, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) openAppDetailsSettings(context)
                }
            }
        }
    }
    fun openScanner() {
        if (hasPermission(Manifest.permission.CAMERA)) {
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
        when (val s = state) {
            is SenderState.ReadyToCapture -> {
                introDismissed = false
                sub = SubRoute.NONE
            }
            is SenderState.Connecting -> sub = SubRoute.NONE
            is SenderState.Casting -> if (lockTipPending) {
                lockTipPending = false
                snackbarScope.launch { snackbar.showSnackbar(SenderCopy.LOCK_SCREEN_TIP) }
            }
            is SenderState.Ended -> {
                lockTipPending = false
                val text = SenderCopy.endedSnackbar(s.reason, s.target?.name, s.failure)
                if (text != null) {
                    val action = SenderCopy.endedAction(s.reason)
                    // acknowledge() 会把状态切回 Idle 并重启本 LaunchedEffect；Snackbar 必须在不随 state 取消的作用域里显示
                    snackbarScope.launch {
                        val shown = s.detail ?: text
                        val r = snackbar.showSnackbar(
                            shown,
                            actionLabel = action?.label,
                            withDismissAction = action != null,
                            duration = if (action != null || SenderCopy.isLongText(shown)) SnackbarDuration.Long else SnackbarDuration.Short,
                        )
                        if (r == SnackbarResult.ActionPerformed) {
                            when (action) {
                                EndedAction.DIAGNOSTICS -> onOpenDiagnostics()
                                EndedAction.RETYPE_CAST_CODE -> sub = SubRoute.CAST_CODE
                                EndedAction.RESCAN -> openScanner()
                                null -> Unit
                            }
                        }
                    }
                    viewModel.acknowledge()
                }
            }
            else -> Unit
        }
    }

    val footer = remember { FooterInset() }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalFooterInset provides footer) {
    Scaffold(
        snackbarHost = {
            // 避开导航栏，有固定页脚时再抬到页脚上方（页脚上报的高度不含导航栏，U1）
            SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = with(density) { footer.heightPx.toDp() }))
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = @Composable {
                ListContent(
                    viewModel, devices, ::openScanner, { sub = SubRoute.CAST_CODE }, { manualIp = true },
                    onDisabledDevice = { row -> snackbarScope.launch { snackbar.showSnackbar(SenderCopy.dlnaOnlyDevice(row.device.name)) } },
                    onOpenSettings, onOpenDiagnostics, onOpenInstallHelp,
                )
            }
            when (val s = state) {
                // 连接中整页替换列表（不再叠在列表上被点穿重复连接，B4）
                is SenderState.Connecting -> WaitingScreen("正在连接 ${s.target.name}…", "加密通道建立中", null, viewModel::cancel)
                is SenderState.AwaitingPin -> PinEntryScreen(s, viewModel::submitPin, viewModel::cancel)
                is SenderState.AwaitingTvConfirm -> WaitingScreen("等待 ${s.target.name} 确认…", "请在电视上选择“允许”", s.deadlineAt, viewModel::cancel)
                is SenderState.Starting -> WaitingScreen("正在建立连接…", "首帧通常在 2 秒内出现", null, viewModel::stop)
                is SenderState.Casting -> {
                    BackHandler(enabled = false) {}
                    CastingScreen(
                        s, viewModel::setQuality, viewModel::setAutoAdjust, viewModel::setAudioEnabled, viewModel::stop,
                        onOpenAppSettings = { openAppDetailsSettings(context) },
                        showNotificationTip = !notificationGranted && !notificationTipDismissed,
                        notificationNeedsSettings = notificationPermanentlyDenied,
                        onAllowNotifications = {
                            if (notificationPermanentlyDenied) {
                                openAppNotificationSettings(context)
                            } else if (Build.VERSION.SDK_INT >= 33) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onDismissNotificationTip = { notificationTipDismissed = true },
                    )
                }
                is SenderState.Reconnecting -> ReconnectingScreen(s, viewModel::stop)
                is SenderState.Ended -> if (s.reason in FULL_PAGE_END_REASONS) {
                    EndedScreen(
                        s,
                        onReconnect = {
                            // 重连失败后“重新连接”跳过说明卡，直达系统授权（交互稿 Q11）
                            val t = s.target
                            viewModel.acknowledge()
                            if (t != null) {
                                viewModel.setSkipIntroOnce()
                                viewModel.reconnectTo(t)
                            }
                        },
                        onBack = viewModel::acknowledge,
                        onDiagnostics = { viewModel.acknowledge(); onOpenDiagnostics() },
                    )
                } else {
                    list()
                }
                else -> when (sub) {
                    SubRoute.CAST_CODE -> CastCodeScreen(lan, viewModel::connectCastCode, { manualIp = true }, { sub = SubRoute.NONE })
                    SubRoute.SCANNER -> QrScannerScreen(onResult = { text -> viewModel.connectQr(text).also { if (it) sub = SubRoute.NONE } }, onBack = { sub = SubRoute.NONE })
                    SubRoute.NONE -> list()
                }
            }
        }
    }
    }

    val ready = state as? SenderState.ReadyToCapture
    // 说明卡“下次不再提示”，或重连失败后的“重新连接”（交互稿 Q11）：跳过说明卡直达系统授权
    val skipIntro = remember(ready) { ready != null && (settings?.skipCaptureIntro == true || viewModel.consumeSkipIntroOnce(peek = false)) }
    LaunchedEffect(ready) {
        if (ready != null && skipIntro) {
            introDismissed = true
            lockTipPending = true
            startCaptureFlow()
        }
    }
    if (ready != null && !introDismissed && !skipIntro) {
        CaptureIntroSheet(
            targetName = ready.target.name,
            audioEnabled = settings?.audioEnabled ?: true,
            onAudioChange = { on ->
                if (on && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
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

@Composable
private fun ListContent(
    viewModel: SenderViewModel,
    devices: DeviceListUi,
    onScan: () -> Unit,
    onCastCode: () -> Unit,
    onManualIp: () -> Unit,
    onDisabledDevice: (DeviceRow) -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onInstallHelp: () -> Unit,
) {
    DeviceListScreen(
        ui = devices,
        searchStartedAt = { viewModel.searchStartedAt },
        onDevice = viewModel::connect,
        onDisabledDevice = onDisabledDevice,
        onForget = { viewModel.forgetDevice(it.device.deviceId) },
        onRefresh = viewModel::restartDiscovery,
        onScan = onScan,
        onCastCode = onCastCode,
        onManualIp = onManualIp,
        onSettings = onSettings,
        onDiagnostics = onDiagnostics,
        onInstallHelp = onInstallHelp,
    )
}
