package com.benchan.wireless.screencasting.feature.sender.elder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.ui.theme.LocalElderTypography
import com.benchan.wireless.screencasting.core.ui.theme.ProvideElderTypography
import com.benchan.wireless.screencasting.feature.sender.service.ScreenCastService
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushConfirmDialog
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushViewModel
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSourceKind
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushTarget
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import kotlinx.coroutines.delay

private enum class ElderRoute { HOME, OTHERS, CHOOSE_TV, SETTINGS }

/**
 * 长辈模式入口（F24，交互稿 E01–E12）。冷启动直接进入首页，**不自动发起镜像**（决策 #44）；
 * 页面由 [SenderState] / 推送会话驱动：连接中 E06 → 已认证 E05 预演（每次都显示，最多停留 60 秒）→ 系统录屏提示 → 投屏中 E07
 * → 结束 E09 / 失败 E08。长辈页面字号来自 [ProvideElderTypography]（不改 Density）。
 *
 * @param quickCast 快捷设置图块：连长辈模式里的第一台电视（之后同样先出 E05）。
 * @param openPushRemote 分享页 [打开遥控]：显示 E10。
 * @param notice 一次性常驻提示（例如长辈模式下忽略了扫码配对链接），null = 没有。
 */
@Composable
fun ElderHost(
    versionName: String,
    quickCast: Boolean,
    onQuickCastConsumed: () -> Unit,
    openPushRemote: Boolean,
    onOpenPushRemoteConsumed: () -> Unit,
    onOpenWizard: () -> Unit,
    notice: String? = null,
    onNoticeDismissed: () -> Unit = {},
    vm: ElderViewModel = hiltViewModel(),
    pushVm: PushViewModel = hiltViewModel(),
) {
    ProvideElderTypography {
        ElderHostContent(versionName, quickCast, onQuickCastConsumed, openPushRemote, onOpenPushRemoteConsumed, onOpenWizard, notice, onNoticeDismissed, vm, pushVm)
    }
}

@Composable
private fun ElderHostContent(
    versionName: String,
    quickCast: Boolean,
    onQuickCastConsumed: () -> Unit,
    openPushRemote: Boolean,
    onOpenPushRemoteConsumed: () -> Unit,
    onOpenWizard: () -> Unit,
    externalNotice: String?,
    onExternalNoticeDismissed: () -> Unit,
    vm: ElderViewModel,
    pushVm: PushViewModel,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val push by vm.push.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val others by vm.others.collectAsStateWithLifecycle()
    val attempt by vm.attempt.collectAsStateWithLifecycle()
    val lan by vm.lan.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(ElderRoute.HOME) }
    // 投屏中按“首页”回到首页（投屏继续，首页顶部显示卡片）
    var castHidden by rememberSaveable { mutableStateOf(false) }
    var pushOpen by rememberSaveable { mutableStateOf(false) }
    var lastTarget by remember { mutableStateOf<ElderHomeTarget?>(null) }
    var lastPushTarget by remember { mutableStateOf<PushTarget?>(null) }
    var helpFor by remember { mutableStateOf<Pair<String?, ElderOutcome>?>(null) }
    var confirmPush by remember { mutableStateOf<PushTarget?>(null) }
    var stopMirrorFor by remember { mutableStateOf<PushTarget?>(null) }
    // 本机在 E07 点了“停止投屏”：结束后显示 E09（EMU-F6）
    var stoppedByUser by remember { mutableStateOf(false) }
    // 为放照片而停止的镜像：结束页不弹出（直接打开照片选择器）
    var silentEnd by remember { mutableStateOf(false) }
    // 首页常驻提示（E05 超时等）
    var homeNotice by remember { mutableStateOf<String?>(null) }

    // 没有进行中的镜像时找电视（最近地址直连 + mDNS + SSDP）；投屏中释放
    val idle = state.isTerminal
    DisposableEffect(idle) {
        if (idle) vm.startDiscovery() else vm.stopDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    // —— 录屏授权（Android 14+ 整屏预设，减少一次系统选择，决策 #44）——
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) ScreenCastService.start(context, r.resultCode, r.data!!) else vm.onCaptureDenied()
    }
    fun requestProjection() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
        projection.launch(intent)
    }

    // 长辈自己取消（E05 / E06 的“取消 / 返回”）：会话结束页不弹，直接回首页
    fun cancel() {
        val st = vm.state.value
        if (st !is SenderState.Idle && !st.isTerminal) silentEnd = true
        vm.cancelAttempt()
    }

    fun cast(t: ElderHomeTarget, entry: String) {
        silentEnd = false
        lastTarget = t
        castHidden = false
        stoppedByUser = false
        homeNotice = null
        // 推送中先结束推送（镜像和推送不能同时进行）
        if (push?.isActive == true) { pushVm.stop(); pushVm.dismiss() }
        pushOpen = false
        vm.cast(t, entry)
    }

    // —— 放照片和视频（系统照片选择器，不需要权限）——
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PushViewModel.MAX_ITEMS)) { uris ->
        val t = lastPushTarget ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        WscLog.i("Elder", "elder push: ${uris.size} item(s)")
        pushVm.pushLocal(t, uris, PushSourceKind.PICKER, replace = false)
        pushOpen = true
    }
    fun launchPicker(t: PushTarget, mirrorStopped: Boolean = false) {
        lastPushTarget = t
        when {
            !mirrorStopped && state !is SenderState.Idle && !state.isTerminal -> stopMirrorFor = t
            pushVm.needsConfirmation(t) -> confirmPush = t
            else -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }
    fun photosFor(t: ElderHomeTarget) {
        route = ElderRoute.HOME
        lastTarget = t
        homeNotice = null
        // 找目标期间显示“正在连接…”、防止重复打开照片选择器（UX P0-2）
        vm.findPushTarget(t) { pt -> launchPicker(pt) }
    }

    LaunchedEffect(quickCast) {
        if (!quickCast) return@LaunchedEffect
        onQuickCastConsumed()
        val s = vm.state.value
        if (s !is SenderState.Idle && s !is SenderState.Ended) return@LaunchedEffect
        // 冷启动时等首页目标第一次算出来再决定（PM P1-6）；没有能镜像的电视给出说明
        val first = vm.awaitTargets().firstOrNull { it.canMirror }
        if (first != null) cast(first.target, "TILE") else vm.showFailure("电视", ElderOutcome.NO_TARGET)
    }
    LaunchedEffect(openPushRemote) {
        if (openPushRemote) {
            onOpenPushRemoteConsumed()
            pushOpen = true
        }
    }
    // 手机没连 Wi‑Fi：连上后自动重试一次（连接前与会话结束两种情况都处理，PM P2-21 ④）
    LaunchedEffect(lan) {
        if (lan == null) return@LaunchedEffect
        val a = vm.attempt.value
        val ended = vm.state.value as? SenderState.Ended
        val endedNoWifi = ended != null && ElderOutcomes.of(ended.reason, ended.failure, hasLan = false) == ElderOutcome.NO_WIFI && !stoppedByUser
        if ((a?.outcome == ElderOutcome.NO_WIFI && !a.photos) || endedNoWifi) {
            if (endedNoWifi) vm.acknowledge()
            lastTarget?.let { cast(it, "AUTO_RETRY") }
        }
    }

    val label = attempt?.label ?: lastTarget?.label ?: (state as? SenderState.Casting)?.target?.name ?: "电视"

    // ------------------------------------------------------------------ 页面选择
    helpFor?.let { (l, o) ->
        BackHandler { helpFor = null }
        val facts = remember(l, o) { vm.helpFacts(l, o) }
        ElderHelpScreen(facts, onSend = {
            vm.onHelpOpened(sent = true)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ElderHelpText.build(facts))
            runCatching { context.startActivity(Intent.createChooser(send, "发给家人")) }
        }, onBack = { helpFor = null })
        return
    }
    if (route == ElderRoute.SETTINGS) {
        BackHandler { route = ElderRoute.HOME }
        ElderSettingsScreen(versionName, onChangeTv = onOpenWizard, onExit = { vm.exitElderMode {} }, onBack = { route = ElderRoute.HOME })
        return
    }
    fun openHelp(o: ElderOutcome) {
        vm.onHelpOpened(sent = false)
        helpFor = label to o
    }
    fun openWifi() = runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // E06 连接页 0–1 s 不显示（多数情况直接进 E05 / E07，避免每次闪一下，UX P2-9）；这 1 秒首页按钮不可点
    val waiting = state is SenderState.Connecting || state is SenderState.Starting || (attempt?.finding == true && attempt?.outcome == null && state.isTerminal)
    var showWaiting by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        showWaiting = false
        if (waiting) {
            delay(1_000)
            showWaiting = true
        }
    }

    when (val s = state) {
        is SenderState.Connecting, is SenderState.Starting -> if (showWaiting) {
            BackHandler { cancel() }
            ElderWaitingScreen(ElderCopy.connecting(label), null, onCancel = ::cancel)
            return
        }
        is SenderState.AwaitingTvConfirm -> {
            BackHandler { cancel() }
            ElderWaitingScreen(ElderCopy.TV_CONFIRM, "等待中…", onCancel = ::cancel)
            return
        }
        is SenderState.AwaitingPin -> {
            BackHandler { cancel() }
            ElderPinScreen(s, vm::submitPin, onCancel = ::cancel)
            return
        }
        is SenderState.ReadyToCapture -> {
            // E05 每次都显示（没有“不再显示”）；最多停留 60 秒，超时断开回首页（D1 条件，UX P1-18）
            LaunchedEffect(s) {
                delay(CAPTURE_INTRO_TIMEOUT_MS)
                WscLog.i("Elder", "capture intro timed out")
                cancel()
                homeNotice = "刚才没有开始投屏，想投的时候再按一次"
            }
            BackHandler { cancel() }
            ElderCaptureIntro(onOk = ::requestProjection, onBack = ::cancel)
            return
        }
        is SenderState.Casting, is SenderState.Reconnecting -> if (!castHidden) {
            BackHandler { castHidden = true }
            ElderCastingScreen(label, s, onStop = { stoppedByUser = true; vm.stopCasting() }, onHome = { castHidden = true })
            return
        }
        is SenderState.Ended -> if (silentEnd) {
            LaunchedEffect(s) { silentEnd = false; vm.acknowledge() }
        } else if (homeNotice == null || s.reason != com.benchan.wireless.screencasting.feature.sender.session.EndReason.USER_STOPPED) {
            val outcome = ElderOutcomes.of(s.reason, s.failure, lan != null)
            val l = lastTarget?.label ?: s.target?.name ?: "电视"
            val home = { vm.acknowledge(); vm.clearAttempt(); stoppedByUser = false }
            if (outcome == ElderOutcome.STOPPED || outcome == ElderOutcome.LOCKED) {
                BackHandler { home() }
                ElderEndedScreen(outcome, photos = false, onAgain = { vm.acknowledge(); lastTarget?.let { cast(it, "AGAIN") } }, onHome = home)
            } else {
                BackHandler { home() }
                ElderFailureScreen(
                    outcome, l,
                    onRetry = { vm.acknowledge(); lastTarget?.let { cast(it, "RETRY") } },
                    onHelp = { openHelp(outcome) },
                    onWifi = ::openWifi,
                    onBack = home,
                )
            }
            return
        } else {
            // E05 超时后的主动断开：不显示结束页，回首页并显示提示卡
            LaunchedEffect(s) { vm.acknowledge() }
        }
        else -> if (stoppedByUser && s is SenderState.Idle) {
            // 某些路径停止后直接回到 Idle：本机主动停止仍显示 E09（EMU-F6）
            BackHandler { stoppedByUser = false }
            ElderEndedScreen(ElderOutcome.STOPPED, photos = false, onAgain = { stoppedByUser = false; lastTarget?.let { cast(it, "AGAIN") } }, onHome = { stoppedByUser = false })
            return
        }
    }
    // 连接前的找电视 / 失败（没有局域网、8 秒没找到、不能放照片）
    attempt?.let { a ->
        if (a.outcome != null) {
            BackHandler { vm.clearAttempt() }
            ElderFailureScreen(
                a.outcome, a.label,
                onRetry = { vm.clearAttempt(); lastTarget?.let { if (a.photos) photosFor(it) else cast(it, "RETRY") } },
                onHelp = { openHelp(a.outcome) },
                onWifi = ::openWifi,
                onBack = vm::clearAttempt,
            )
            return
        }
        if (a.finding && state.isTerminal && showWaiting) {
            BackHandler { cancel() }
            ElderWaitingScreen(ElderCopy.connecting(a.label), null, onCancel = ::cancel)
            return
        }
    }
    // E10 推送遥控（大字版）
    val ps = push
    if (pushOpen && ps != null) {
        val ended = ps.ended
        if (ended != null) {
            val home = { pushVm.dismiss(); pushOpen = false }
            BackHandler { home() }
            val failed = ended.failure != null || ended.reason == PushEndReason.START_FAILED || ended.reason == PushEndReason.ALL_FAILED ||
                ended.reason == PushEndReason.DEVICE_LOST || ended.reason == PushEndReason.NETWORK_CHANGED
            if (failed) {
                // 推送异常结束走 E08 的长辈文案（UX P2-23）
                ElderFailureScreen(
                    if (ended.reason == PushEndReason.NETWORK_CHANGED && lan == null) ElderOutcome.NO_WIFI else if (ended.reason == PushEndReason.DEVICE_LOST) ElderOutcome.DISCONNECTED else ElderOutcome.OTHER,
                    ps.target.name,
                    onRetry = { home(); lastPushTarget?.let(::launchPicker) },
                    onHelp = { openHelp(ElderOutcome.OTHER) },
                    onWifi = ::openWifi,
                    onBack = home,
                )
            } else {
                ElderEndedScreen(ElderOutcome.STOPPED, photos = true, onAgain = { home(); lastPushTarget?.let(::launchPicker) }, onHome = home)
            }
        } else {
            BackHandler { pushOpen = false }
            ElderPushScreen(
                ps,
                ElderPushActions(
                    prev = pushVm::previous, next = pushVm::next, toggle = pushVm::togglePlay, stop = pushVm::stop,
                    home = { pushOpen = false }, retry = pushVm::retry, help = { openHelp(ElderOutcome.OTHER) },
                    acceptTranscode = { pushVm.acceptTranscode(videoOnly = false, autoForList = true) },
                    declineTranscode = { pushVm.cancelTranscode(); if (ps.items.size > 1) pushVm.skipItem() else pushVm.stop() },
                ),
            )
        }
        return
    }

    when (route) {
        ElderRoute.OTHERS -> {
            BackHandler { route = ElderRoute.HOME }
            ElderOthersScreen(
                others, onBack = { route = ElderRoute.HOME },
                onCast = { o -> route = ElderRoute.HOME; cast(ElderHomeTarget(o.device.name, o.device.name, o.device.deviceId, o.device.dlna?.udn, pinned = false), "OTHER_DEVICE") },
                onPhotos = { o -> route = ElderRoute.HOME; vm.targetFor(o.device)?.let(::launchPicker) },
                onHelp = { openHelp(ElderOutcome.NOT_FOUND) },
            )
        }
        ElderRoute.CHOOSE_TV -> {
            BackHandler { route = ElderRoute.HOME }
            ElderChooseTvScreen(targets.filter { it.canPush }.map { it.target }, onPick = ::photosFor, onBack = { route = ElderRoute.HOME })
        }
        else -> {
            val casting = state is SenderState.Casting || state is SenderState.Reconnecting
            val active = when {
                casting -> ElderActive(ElderCopy.castingTo(label), "回到投屏页面", onOpen = { castHidden = false }, onStop = { stoppedByUser = true; castHidden = false; vm.stopCasting() })
                ps?.isActive == true -> ElderActive("正在放到 ${ps.target.name}", "回到放照片页面", onOpen = { pushOpen = true }, onStop = pushVm::stop)
                else -> null
            }
            val notice = (homeNotice ?: externalNotice)?.let { text ->
                text to { if (homeNotice != null) homeNotice = null else onExternalNoticeDismissed() }
            }
            ElderHomeScreen(
                targets, active, notice, busy = waiting,
                onCast = { cast(it, "HOME_BUTTON") },
                onPhotos = {
                    val pushable = targets.filter { it.canPush }
                    when {
                        pushable.size > 1 -> route = ElderRoute.CHOOSE_TV
                        pushable.size == 1 -> photosFor(pushable[0].target)
                        // 家人设置的电视此刻都不能放照片：直接说明，不空等 8 秒（PM P1-4）
                        else -> targets.firstOrNull()?.let { t ->
                            if (t.found == true) vm.showFailure(t.target.label, ElderOutcome.CANNOT_PUSH, photos = true) else photosFor(t.target)
                        }
                    }
                },
                onPhotosFor = ::photosFor,
                onPickOne = { route = ElderRoute.OTHERS },
                onOthers = { route = ElderRoute.OTHERS },
                onSettings = { route = ElderRoute.SETTINGS },
            )
        }
    }

    confirmPush?.let { t ->
        PushConfirmDialog(t, onCancel = { confirmPush = null }, onConfirm = {
            pushVm.confirm(t)
            confirmPush = null
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        })
    }
    stopMirrorFor?.let { t ->
        AlertDialog(
            onDismissRequest = { stopMirrorFor = null },
            text = { Text("要先停止投屏，才能放照片和视频。现在停止投屏吗？", style = LocalElderTypography.current.body) },
            // 主按钮在上（UX P1-12）
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElderButton("停止投屏，放照片", {
                        stopMirrorFor = null
                        silentEnd = true
                        vm.stopCasting()
                        launchPicker(t, mirrorStopped = true)
                    })
                    ElderButton("不了", { stopMirrorFor = null }, kind = ElderButtonKind.OUTLINED)
                }
            },
        )
    }
}

private const val CAPTURE_INTRO_TIMEOUT_MS = 60_000L
