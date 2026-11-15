package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.compose.ui.platform.LocalResources
import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.feature.control.ControlEvent
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
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushFlowCallbacks
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushFullScreen
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushOverlays
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushViewModel
import com.benchan.wireless.screencasting.feature.sender.ui.push.beginPushTo
import com.benchan.wireless.screencasting.feature.sender.ui.push.guardMirror
import com.benchan.wireless.screencasting.feature.sender.ui.push.beginPush
import com.benchan.wireless.screencasting.feature.sender.ui.push.rememberPushFlow
import kotlinx.coroutines.flow.first
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
    entry: SenderEntry? = null,
    onEntryConsumed: () -> Unit = {},
    listTopCard: (@Composable () -> Unit)? = null,
    viewModel: SenderViewModel = hiltViewModel(),
    pushVm: PushViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lan by viewModel.lan.collectAsStateWithLifecycle()
    val controlUi by viewModel.controlUi.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var sub by rememberSaveable { mutableStateOf(SubRoute.NONE) }
    var manualIp by rememberSaveable { mutableStateOf(false) }
    var introDismissed by remember { mutableStateOf(false) }
    // 跳过说明卡时，进入投屏后补一次“锁屏会停止投屏”提示（D6）
    var lockTipPending by remember { mutableStateOf(false) }
    // 08B “改用推送”找不到可推送的本应用接收端时：滚动到“电视自带投屏”分组
    var scrollToDlna by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // DLNA 推送流程（交互稿第 13 章 P01–P15）
    val push = rememberPushFlow()
    val pushCb = remember {
        PushFlowCallbacks(
            discoveryStartedAt = { viewModel.searchStartedAt },
            connectMirror = viewModel::connect,
            rowForWscId = { id -> viewModel.devices.value.let { d -> (d.recent + d.nearby).firstOrNull { it.device.deviceId == id } } },
            restartDiscovery = viewModel::restartDiscovery,
            openDiagnostics = onOpenDiagnostics,
            openInstallHelp = onOpenInstallHelp,
        )
    }

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // 发现：在列表页可见时运行（离开即释放 MulticastLock）
    val listVisible = sub == SubRoute.NONE && !push.remoteOpen && !push.url &&
        (state is SenderState.Idle || state is SenderState.Ended || state is SenderState.Connecting)
    DisposableEffect(listVisible) {
        if (listVisible) viewModel.startDiscovery() else viewModel.stopDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(quickCast) {
        if (!quickCast) return@LaunchedEffect
        val s = viewModel.state.value
        // 推送中先确认结束推送（P15，第 1 轮 PM P0-3）
        val go = {
            val ok = viewModel.quickConnectRecent()
            if (!ok) snackbarScope.launch { snackbar.showSnackbar(res.getString(R.string.sender_no_recent)) }
        }
        if (s is SenderState.Idle || s is SenderState.Ended) push.guardMirror(pushVm) { go() }
        // 处理完立即复位（复位会重启本 effect，所以 Snackbar 放到独立作用域），保证每次点图块都能再次触发（B3）
        onQuickCastConsumed()
    }
    // F14：从分享页 / Direct Share 桌面快捷方式进来（一次性事件）
    var mirrorHint by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(entry) {
        val e = entry ?: return@LaunchedEffect
        onEntryConsumed()
        when (e) {
            SenderEntry.OpenPushRemote -> if (pushVm.session.value != null) push.remoteOpen = true
            SenderEntry.MirrorHint -> mirrorHint = true
            is SenderEntry.PushTo -> {
                val row = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    viewModel.devices.first { d -> (d.recent + d.nearby + d.dlna).any { it.device.dlna?.udn == e.udn } }
                        .let { d -> (d.recent + d.nearby + d.dlna).first { it.device.dlna?.udn == e.udn } }
                }
                val target = row?.let { pushVm.targetFor(it.device, viewModel.searchStartedAt) }
                if (target != null) push.beginPushTo(target, pushVm) else snackbarScope.launch { snackbar.showSnackbar(res.getString(R.string.sender_push_target_missing)) }
            }
        }
    }
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            val link = deepLink
            onDeepLinkConsumed()
            push.guardMirror(pushVm) {
                if (!viewModel.connectQr(link)) snackbarScope.launch { snackbar.showSnackbar(res.getString(R.string.sender_bad_link)) }
            }
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
    // 开启反向控制时 Android 14+ 强制共享整个屏幕（用户决策 D1）：系统不提供可靠的“单应用共享”判定，
    // 单应用共享下拿不到窗口位置，坐标无法换算，也可能盲操作其他应用
    val forceWholeScreen = settings?.remoteControlEnabled == true && Build.VERSION.SDK_INT >= 34
    fun requestProjection() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val intent = if (forceWholeScreen) {
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
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
                    val r = snackbar.showSnackbar(res.getString(R.string.sender_camera_denied), actionLabel = res.getString(R.string.sender_camera_denied_action), duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) sub = SubRoute.CAST_CODE
                } else {
                    val r = snackbar.showSnackbar(res.getString(R.string.sender_camera_permanently_denied), actionLabel = res.getString(R.string.sender_open_settings), duration = SnackbarDuration.Long)
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
                    val r = snackbar.showSnackbar(res.getString(R.string.sender_auto_downgraded), actionLabel = res.getString(R.string.sender_undo), duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoAutoDowngrade()
                }
                SenderEvent.Recovered -> snackbar.showSnackbar(res.getString(R.string.sender_recovered))
                is SenderEvent.PinIncorrect -> Unit
            }
        }
    }
    // 反向控制结束（交互稿 R04 出口 / 12 章状态表“结束”）：本机收回 → “已收回控制，投屏继续”；其他原因 → “已收回控制”
    LaunchedEffect(Unit) {
        viewModel.controlEvents.collect { e ->
            if (e is ControlEvent.Ended) {
                // 结束原因分别提示（PM 走查 G5）；未授权过的锁屏 / 拒绝不提示（协调器只在真正被控制过时发事件）
                when (e.reason) {
                    ControlReason.REVOKED -> snackbar.showSnackbar(res.getString(R.string.sender_control_revoked_continue))
                    ControlReason.RECEIVER_END -> snackbar.showSnackbar(res.getString(R.string.sender_control_receiver_end))
                    ControlReason.SERVICE_OFF -> {
                        val r = snackbar.showSnackbar(res.getString(R.string.sender_control_service_off), actionLabel = res.getString(R.string.sender_control_turn_on), duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) {
                            runCatching { context.startActivity(android.content.Intent(context, com.benchan.wireless.screencasting.feature.control.ui.AccessibilityGuideActivity::class.java)) }
                        }
                    }
                    ControlReason.SESSION_END -> Unit
                    else -> snackbar.showSnackbar(res.getString(R.string.sender_control_revoked))
                }
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
            is SenderState.Casting -> if (lockTipPending || mirrorHint) {
                mirrorHint = false
                if (lockTipPending) snackbarScope.launch { snackbar.showSnackbar(res.getString(R.string.sender_lock_screen_tip)) }
                lockTipPending = false
            }
            is SenderState.Ended -> {
                lockTipPending = false
                val text = SenderCopy.endedSnackbar(s.reason, s.target?.name, s.failure)
                if (text != null) {
                    val action = SenderCopy.endedAction(s.reason)
                    // acknowledge() 会把状态切回 Idle 并重启本 LaunchedEffect；Snackbar 必须在不随 state 取消的作用域里显示
                    snackbarScope.launch {
                        val shown = s.detail ?: text.resolve(res)
                        val r = snackbar.showSnackbar(
                            shown,
                            actionLabel = action?.label?.let(res::getString),
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
            val pushFull = (state is SenderState.Idle || state is SenderState.Ended) && PushFullScreen(push, pushVm, pushCb, snackbar, snackbarScope)
            val list = @Composable {
                ListContent(
                    viewModel, devices, ::openScanner, { sub = SubRoute.CAST_CODE }, { manualIp = true },
                    onDisabledDevice = { _ -> snackbarScope.launch { snackbar.showSnackbar(res.getString(R.string.sender_device_disabled)) } },
                    onOpenSettings, onOpenDiagnostics, onOpenInstallHelp,
                    onDevice = { row -> push.guardMirror(pushVm) { viewModel.connect(row) } },
                    onPush = { row -> push.beginPush(row, pushVm, pushCb) },
                    onDlnaDetail = { row -> pushVm.targetFor(row.device, viewModel.searchStartedAt)?.let { push.detail = it } },
                    onOpenPush = { push.remoteOpen = true },
                    onUnencryptedInfo = { push.unencryptedInfo = true },
                    scrollToDlna = scrollToDlna,
                    topCard = {
                        if (mirrorHint) MirrorHintCard { mirrorHint = false }
                        listTopCard?.invoke()
                    },
                )
            }
            if (!pushFull) when (val s = state) {
                // 连接中整页替换列表（不再叠在列表上被点穿重复连接，B4）
                is SenderState.Connecting -> WaitingScreen(stringResource(R.string.sender_connecting_to, s.target.name), stringResource(R.string.sender_connecting_detail), null, viewModel::cancel)
                is SenderState.AwaitingPin -> PinEntryScreen(s, viewModel::submitPin, viewModel::cancel)
                is SenderState.AwaitingTvConfirm -> WaitingScreen(stringResource(R.string.sender_waiting_confirm, s.target.name), stringResource(R.string.sender_waiting_confirm_detail), s.deadlineAt, viewModel::cancel)
                is SenderState.Starting -> WaitingScreen(stringResource(R.string.sender_starting), stringResource(R.string.sender_starting_detail), null, viewModel::stop)
                is SenderState.Casting -> {
                    BackHandler(enabled = false) {}
                    CastingScreen(
                        s, viewModel::setQuality, viewModel::setAutoAdjust, viewModel::setAudioEnabled, viewModel::stop,
                        control = controlUi,
                        onRevokeControl = viewModel::revokeControl,
                        onContinueControl = viewModel::continueControl,
                        game = GameActions(viewModel::answerGameIntro, viewModel::dismissGameHint, viewModel::gameHintAction, onOpenInstallHelp),
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
                            if (t != null) push.guardMirror(pushVm) {
                                viewModel.setSkipIntroOnce()
                                viewModel.reconnectTo(t)
                            }
                        },
                        onUsePush = {
                            // 对方是本应用接收端且发现了它的 DLNA → 直接为它打开内容面板；否则回列表并滚到“电视自带投屏”
                            val id = s.target?.deviceId
                            viewModel.acknowledge()
                            val row = devices.let { d -> (d.recent + d.nearby).firstOrNull { it.device.deviceId == id && it.device.canPush } }
                            val target = row?.let { pushVm.targetFor(it.device, viewModel.searchStartedAt) }
                            if (target != null) push.beginPushTo(target, pushVm) else scrollToDlna++
                        },
                        onBack = viewModel::acknowledge,
                        onDiagnostics = { viewModel.acknowledge(); onOpenDiagnostics() },
                    )
                } else {
                    list()
                }
                else -> when (sub) {
                    SubRoute.CAST_CODE -> CastCodeScreen(
                        lan,
                        { code ->
                            if (pushVm.session.value?.isActive == true) {
                                push.guardMirror(pushVm) { viewModel.connectCastCode(code) }
                                true
                            } else viewModel.connectCastCode(code)
                        },
                        { manualIp = true }, { sub = SubRoute.NONE },
                    )
                    SubRoute.SCANNER -> QrScannerScreen(
                        onResult = { text ->
                            if (pushVm.session.value?.isActive == true) {
                                sub = SubRoute.NONE
                                push.guardMirror(pushVm) { viewModel.connectQr(text) }
                                true
                            } else viewModel.connectQr(text).also { if (it) sub = SubRoute.NONE }
                        },
                        onBack = { sub = SubRoute.NONE },
                    )
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
            forceWholeScreen = forceWholeScreen,
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

    PushOverlays(push, pushVm, pushCb, snackbar, snackbarScope)

    if (manualIp) {
        ManualIpDialog(onDismiss = { manualIp = false }) { text ->
            if (pushVm.session.value?.isActive == true) {
                manualIp = false
                push.guardMirror(pushVm) { viewModel.connectManual(text) }
                true
            } else viewModel.connectManual(text).also { if (it) manualIp = false }
        }
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
    onDevice: (DeviceRow) -> Unit,
    onPush: (DeviceRow) -> Unit,
    onDlnaDetail: (DeviceRow) -> Unit,
    onOpenPush: () -> Unit,
    onUnencryptedInfo: () -> Unit,
    scrollToDlna: Int,
    topCard: @Composable () -> Unit = {},
) {
    DeviceListScreen(
        topCard = topCard,
        ui = devices,
        searchStartedAt = { viewModel.searchStartedAt },
        onDevice = onDevice,
        onPush = onPush,
        onDlnaDetail = onDlnaDetail,
        onOpenPush = onOpenPush,
        onUnencryptedInfo = onUnencryptedInfo,
        scrollToDlna = scrollToDlna,
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
