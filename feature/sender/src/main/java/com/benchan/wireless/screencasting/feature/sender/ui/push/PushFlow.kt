package com.benchan.wireless.screencasting.feature.sender.ui.push

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.feature.sender.ui.DeviceRow
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSourceKind
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushTarget
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEvent
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** P15 互斥确认的三种情况（镜像入口一律走 [StopPushThen]）。 */
internal sealed interface PushMutex {
    data class StopMirror(val target: PushTarget) : PushMutex
    data class StopPushThen(val go: () -> Unit) : PushMutex
    data class SwitchTv(val target: PushTarget) : PushMutex
}

/**
 * 推送流程的界面状态（交互稿 P01 → P02 → P03/P04 → P05… → P14）。
 * 导航标志可保存（选择器返回时 Activity 被重建也不丢）；目标设备放在 [PushViewModel.pendingTarget]（第 1 轮 PM P1-7）。
 */
@Stable
internal class PushFlowState(
    confirm: Boolean = false, content: Boolean = false, replacing: Boolean = false, url: Boolean = false,
    lastUrl: String = "", remoteOpen: Boolean = false,
) {
    var confirm by mutableStateOf(confirm)
    var content by mutableStateOf(content)
    var replacing by mutableStateOf(replacing)
    var url by mutableStateOf(url)
    var lastUrl by mutableStateOf(lastUrl)
    var remoteOpen by mutableStateOf(remoteOpen)
    var playlist by mutableStateOf(false)
    var mutex by mutableStateOf<PushMutex?>(null)
    var detail by mutableStateOf<PushTarget?>(null)
    var unencryptedInfo by mutableStateOf(false)
    var formatWarning by mutableStateOf<(() -> Unit)?>(null)

    companion object {
        val Saver: Saver<PushFlowState, Any> = Saver(
            save = { listOf(it.confirm, it.content, it.replacing, it.url, it.lastUrl, it.remoteOpen) },
            restore = { v ->
                val l = v as List<*>
                PushFlowState(l[0] as Boolean, l[1] as Boolean, l[2] as Boolean, l[3] as Boolean, l[4] as String, l[5] as Boolean)
            },
        )
    }
}

@Composable
internal fun rememberPushFlow(): PushFlowState = rememberSaveable(saver = PushFlowState.Saver) { PushFlowState() }

internal class PushFlowCallbacks(
    val discoveryStartedAt: () -> Long,
    val connectMirror: (DeviceRow) -> Unit,
    val rowForWscId: (String) -> DeviceRow?,
    val restartDiscovery: () -> Unit,
    val openDiagnostics: () -> Unit,
    val openInstallHelp: () -> Unit,
)

/** 设备列表 → 推送（含 P15 互斥判断）。 */
internal fun PushFlowState.beginPush(row: DeviceRow, vm: PushViewModel, cb: PushFlowCallbacks) {
    val target = vm.targetFor(row.device, cb.discoveryStartedAt()) ?: return
    beginPushTo(target, vm)
}

internal fun PushFlowState.beginPushTo(target: PushTarget, vm: PushViewModel) {
    val cur = vm.session.value
    when {
        cur?.isActive == true && cur.target.udn == target.udn -> remoteOpen = true
        cur?.isActive == true -> mutex = PushMutex.SwitchTv(target)
        vm.mirroring -> mutex = PushMutex.StopMirror(target)
        else -> proceed(target, vm)
    }
}

/**
 * 所有镜像入口（设备列表、图块、深链、扫码、投屏码、手动 IP、重新连接）先经过这里：推送中先确认结束推送（P15，第 1 轮 PM P0-3）。
 */
internal fun PushFlowState.guardMirror(vm: PushViewModel, go: () -> Unit) {
    if (vm.session.value?.isActive == true) mutex = PushMutex.StopPushThen(go) else go()
}

private fun PushFlowState.proceed(target: PushTarget, vm: PushViewModel) {
    vm.pendingTarget = target
    replacing = false
    if (vm.needsConfirmation(target)) confirm = true else content = true
}

/** 打开内容面板（换内容 / 失败后换文件）。 */
private fun PushFlowState.openContent(target: PushTarget, vm: PushViewModel, replace: Boolean) {
    vm.pendingTarget = target
    replacing = replace
    content = true
}

/** 整页内容（遥控页 / 结束页 / 网址页）；没有则返回 false。 */
@Composable
internal fun PushFullScreen(flow: PushFlowState, vm: PushViewModel, cb: PushFlowCallbacks, snackbar: SnackbarHostState, scope: CoroutineScope): Boolean {
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session
    val t = vm.pendingTarget ?: s?.target
    if (flow.url && t != null) {
        BackHandler { flow.url = false; flow.content = true }
        PushUrlScreen(t.name, PushCopy.isAudioOnly(t.sink), flow.lastUrl, onBack = { flow.url = false; flow.content = true }) { url ->
            flow.lastUrl = url
            flow.url = false
            vm.pushUrl(t, url, flow.replacing)
            flow.remoteOpen = true
        }
        return true
    }
    if (!flow.remoteOpen || s == null) return false
    val ended = s.ended
    if (ended != null) {
        BackHandler { vm.dismiss(); flow.remoteOpen = false }
        PushEndScreen(
            ended, s.target.name,
            onAgain = {
                vm.dismiss()
                flow.remoteOpen = false
                flow.openContent(s.target, vm, replace = false)
            },
            onResume = { vm.resumeLast() },
            onBack = { vm.dismiss(); flow.remoteOpen = false },
            onDiagnostics = cb.openDiagnostics,
        )
        return true
    }
    BackHandler { flow.remoteOpen = false }
    // 遥控页在前台：轮询 1 s；离开或 App 进后台：5 s（引擎）
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.setForeground(true)
            if (e == Lifecycle.Event.ON_PAUSE) vm.setForeground(false)
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            vm.setForeground(false)
        }
    }
    PushRemoteScreen(
        s, vm,
        PushRemoteActions(
            onBack = { flow.remoteOpen = false },
            onChangeContent = { flow.openContent(s.target, vm, replace = true) },
            onShowPlaylist = { flow.playlist = true },
            onFailureAction = { a -> onFailureAction(a, s, flow, vm, cb, snackbar, scope) },
            onCancelLoading = {
                vm.cancelLoading()
                flow.remoteOpen = false
                flow.openContent(s.target, vm, replace = false)
            },
            onCancelTranscode = {
                vm.cancelTranscode()
                scope.launch {
                    val r = snackbar.showSnackbar("已取消转换", actionLabel = "直接尝试原文件", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.tryOriginal()
                }
            },
            onChooseTranscode = { vm.offerTranscodeFor(s.engine.index) },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        ),
    )
    return true
}

private fun onFailureAction(
    a: PushCopy.Action, s: PushSessionState, flow: PushFlowState, vm: PushViewModel, cb: PushFlowCallbacks,
    snackbar: SnackbarHostState, scope: CoroutineScope,
) {
    when (a) {
        PushCopy.Action.TRANSCODE -> vm.offerTranscodeFor(s.engine.index)
        PushCopy.Action.CHANGE_FILE, PushCopy.Action.LOCAL_FILE, PushCopy.Action.RESELECT -> flow.openContent(s.target, vm, replace = true)
        PushCopy.Action.EDIT_URL -> {
            vm.pendingTarget = s.target; flow.replacing = true; flow.url = true
        }
        PushCopy.Action.INSTALL_APP -> cb.openInstallHelp()
        PushCopy.Action.USE_MIRROR -> {
            val row = s.target.description.wscId?.let(cb.rowForWscId)
            if (row == null) {
                // 找不到这台设备的镜像入口：说明原因，不静默（第 1 轮 PM P2-20）
                scope.launch { snackbar.showSnackbar("暂时没找到 ${s.target.name} 的镜像入口，请回到设备列表重新选择") }
            } else {
                vm.stop(); vm.dismiss(); flow.remoteOpen = false
                cb.connectMirror(row)
            }
        }
        PushCopy.Action.RETRY -> vm.retry()
        PushCopy.Action.OTHER_DEVICE -> { vm.stop(); vm.dismiss(); flow.remoteOpen = false }
        PushCopy.Action.RESCAN -> { vm.stop(); vm.dismiss(); flow.remoteOpen = false; cb.restartDiscovery() }
        PushCopy.Action.DIAGNOSTICS -> cb.openDiagnostics()
        PushCopy.Action.TRY_ORIGINAL -> vm.tryOriginal()
        PushCopy.Action.CANCEL -> Unit
    }
}

/** 浮层：P02 确认、P03 内容、P08 列表、P10 转换提议、P15 互斥、设备详情、不加密说明、格式提醒、Snackbar。 */
@Composable
internal fun PushOverlays(flow: PushFlowState, vm: PushViewModel, cb: PushFlowCallbacks, snackbar: SnackbarHostState, scope: CoroutineScope) {
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PushViewModel.MAX_ITEMS)) { uris ->
        val t = vm.pendingTarget ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) { flow.content = true; return@rememberLauncherForActivityResult }
        vm.pushLocal(t, uris, PushSourceKind.PICKER, flow.replacing)
        flow.remoteOpen = true
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val t = vm.pendingTarget ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) { flow.content = true; return@rememberLauncherForActivityResult }
        vm.pushLocal(t, uris, PushSourceKind.FILES, flow.replacing)
        flow.remoteOpen = true
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is PushUiEvent.Skipped -> {
                    val parts = listOfNotNull(
                        e.cloudFiles.takeIf { it > 0 }?.let { "有 $it 个文件需要先下载到手机" },
                        e.unreadable.takeIf { it > 0 }?.let { "有 $it 个文件无法读取，请重新选择" },
                        if (e.truncated) "最多推送 100 项，已保留前 100 项" else null,
                    )
                    snackbar.showSnackbar(parts.joinToString("；"))
                }
                is PushUiEvent.FormatWarning -> flow.formatWarning = e.go
            }
        }
    }
    // 引擎一次性事件（第 1 轮 PM P1-4）
    LaunchedEffect(Unit) {
        vm.engineEvents.collect { e ->
            when (e) {
                PushEvent.NoResponse -> snackbar.showSnackbar("电视没有响应")
                PushEvent.SeekUnsupported -> snackbar.showSnackbar("这台电视不支持拖动进度")
                else -> Unit
            }
        }
    }
    // 列表里某项失败、已自动跳过（交互稿 P08）
    LaunchedEffect(s?.skipped) {
        val sk = s?.skipped ?: return@LaunchedEffect
        if (System.currentTimeMillis() - sk.at < 5_000) {
            val r = snackbar.showSnackbar("第 ${sk.index + 1} 项无法播放，已跳到下一项", actionLabel = "查看", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) flow.playlist = true
        }
    }
    // 推送在遥控页之外结束（电视停止 / 被接管等）：列表上用 Snackbar 告知，不弹整页
    LaunchedEffect(s?.ended, flow.remoteOpen) {
        val ended = s?.ended ?: return@LaunchedEffect
        if (!flow.remoteOpen) {
            vm.dismiss()
            if (ended.reason != PushEndReason.USER_STOPPED || ended.failure != null) {
                scope.launch { snackbar.showSnackbar(PushCopy.endTitle(ended, s.target.name).first) }
            }
        }
    }

    val target = vm.pendingTarget
    if (flow.confirm && target != null) {
        PushConfirmDialog(target, onCancel = { flow.confirm = false }, onConfirm = {
            vm.confirm(target)
            flow.confirm = false
            flow.content = true
        })
    }
    if (flow.content && target != null) {
        val audioOnly = PushCopy.isAudioOnly(target.sink)
        PushContentSheet(
            target.name, audioOnly, flow.replacing, ownApp = target.description.isOwnApp,
            onPicker = { flow.content = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onFiles = {
                flow.content = false
                files.launch(if (audioOnly) arrayOf("audio/*") else arrayOf("video/*", "audio/*", "image/*"))
            },
            onUrl = { flow.content = false; flow.url = true },
            onDismiss = { flow.content = false },
        )
    }
    if (flow.playlist && s != null && s.isActive) {
        PlaylistSheet(
            s.items, s.engine.index,
            transcodingIndex = s.transcode?.takeIf { it.phase == TranscodeUi.Phase.RUNNING }?.index,
            transcodeProgress = s.transcode?.progress ?: 0,
            onSelect = { vm.select(it); flow.playlist = false },
            onTranscode = { vm.offerTranscodeFor(it); flow.playlist = false },
            onChangeContent = { flow.playlist = false; flow.openContent(s.target, vm, replace = true) },
            onDismiss = { flow.playlist = false },
        )
    }
    val offer = s?.transcode?.takeIf { it.phase == TranscodeUi.Phase.OFFER || it.phase == TranscodeUi.Phase.NO_SPACE || it.phase == TranscodeUi.Phase.PHONE_UNSUPPORTED }
    if (offer != null && s.isActive) {
        TranscodeOfferSheet(
            offer, s.target.name, s.items.getOrNull(offer.index)?.title.orEmpty(),
            onTranscode = { videoOnly, auto -> vm.acceptTranscode(videoOnly, auto); flow.remoteOpen = true },
            onTryOriginal = { vm.tryOriginal(); flow.remoteOpen = true },
            onChangeFile = { vm.dismissTranscodeOffer(); flow.openContent(s.target, vm, replace = true) },
            onDismiss = {
                vm.dismissTranscodeOffer()
                // 推送前的提议被关掉：回到内容面板（第 1 轮 UX P2-18）
                if (s.engine.phase == PushPhase.AWAITING_CHOICE && s.items.size == 1) flow.openContent(s.target, vm, replace = true)
            },
        )
    }
    val transcodeTail = if (vm.transcodingNow) "正在进行的视频转换也会取消。" else ""
    when (val m = flow.mutex) {
        is PushMutex.StopMirror -> PushMutexDialog(
            "先停止屏幕镜像？",
            (vm.mirrorTargetName?.let { "正在把屏幕镜像到 $it。" } ?: "正在镜像屏幕。") + "镜像和推送不能同时进行，推送到 ${m.target.name} 前会先停止镜像。",
            "停止镜像并推送",
            onCancel = { flow.mutex = null },
        ) {
            flow.mutex = null
            vm.stopMirroring()
            flow.proceed(m.target, vm)
        }
        is PushMutex.StopPushThen -> PushMutexDialog(
            "先结束推送？",
            "正在推送到 ${s?.target?.name ?: "电视"}。开始镜像会结束推送，电视会停止播放。$transcodeTail",
            "结束推送并镜像",
            onCancel = { flow.mutex = null },
        ) {
            flow.mutex = null
            vm.stop(); vm.dismiss()
            flow.remoteOpen = false
            m.go()
        }
        is PushMutex.SwitchTv -> PushMutexDialog(
            "改为推送到 ${m.target.name}？",
            "${s?.target?.name ?: "当前电视"}会停止播放。$transcodeTail",
            "改为推送到 ${m.target.name}",
            onCancel = { flow.mutex = null },
        ) {
            flow.mutex = null
            vm.stop(); vm.dismiss()
            flow.remoteOpen = false
            flow.proceed(m.target, vm)
        }
        null -> Unit
    }
    flow.formatWarning?.let { go ->
        PushMutexDialog(
            "电视可能不支持这个格式，仍要尝试？", "电视声明的格式里没有它。可以先试一下，放不了的话 20 秒内会告诉你。", "仍要尝试",
            onCancel = { flow.formatWarning = null },
        ) {
            flow.formatWarning = null
            go()
            flow.remoteOpen = true
        }
    }
    flow.detail?.let { d ->
        DlnaDeviceDetailDialog(
            d, vm.pushedBefore(d.udn), onInstallHelp = cb.openInstallHelp, onForget = { vm.forget(d.udn) },
            onDismiss = { flow.detail = null },
        )
    }
    if (flow.unencryptedInfo) UnencryptedInfoDialog { flow.unencryptedInfo = false }
}
