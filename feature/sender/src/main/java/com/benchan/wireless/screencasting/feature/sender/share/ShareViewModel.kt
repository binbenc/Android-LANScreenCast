package com.benchan.wireless.screencasting.feature.sender.share

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.uiText
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.core.data.settings.AppSettings
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.discovery.DeviceDiscovery
import com.benchan.wireless.screencasting.core.discovery.DeviceProtocol
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.net.LanInfo
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import com.benchan.wireless.screencasting.feature.sender.push.MediaPushService
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushInput
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSourceKind
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushTarget
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** 分享页阶段（交互稿第 14 章“分享页状态”表）。 */
enum class ShareStage { CHECKING, BLOCKED, WEB_URL, PICK, DIRECT_FINDING, STARTING, STARTED }

/** 一项都推不了的结果卡（交互稿 S05 ⑥–⑩）。 */
sealed interface ShareBlock {
    data object NothingSupported : ShareBlock
    data class Unreadable(val reason: UnreadableReason) : ShareBlock
    data class NoSpace(val plan: SpacePlan) : ShareBlock
    data object NoUrl : ShareBlock

    /** 复制项全部失败（PM P1-1：不能静默无反应）。 */
    data object PrepareFailed : ShareBlock
}

/** 分享进来的内容（到达即检查之后）。 */
data class ShareContent(
    val items: List<SharedItem> = emptyList(),
    val url: String? = null,
    val ignored: Int = 0,
    val unreadable: Int = 0,
    val unreadableReason: UnreadableReason = UnreadableReason.GENERIC,
    val truncated: Boolean = false,
) {
    val kinds: List<MediaKind> get() = items.map { it.kind }
    val audioOnly: Boolean get() = items.isNotEmpty() && items.all { it.kind == MediaKind.AUDIO }
}

data class ShareUi(
    val stage: ShareStage = ShareStage.CHECKING,
    val content: ShareContent = ShareContent(),
    val block: ShareBlock? = null,
    val batchId: String? = null,
    /** Direct Share 点的那台（交互稿 S08）。 */
    val directName: String? = null,
    /** Direct Share 落回列表时顶部的提示（找不到 / 只能推音乐）。 */
    val directMissed: UiText? = null,
    /** Direct Share 点的那台（网页地址选了“仍然推送”后继续用它，PM P2-8）。 */
    val directUdn: String? = null,
    val target: PushTarget? = null,
    val sessionId: String? = null,
    /** S05 ⑦“在本应用里重新选”：点设备后打开本应用的内容面板。 */
    val reselect: Boolean = false,
    /** 网页地址在 S04 选了“仍然推送”。 */
    val webAccepted: Boolean = false,
)

enum class ShareGroup { FAMILY, RECENT, DLNA, OWN, DISABLED }

/** 选设备页的一行（交互稿 S01，行样式同 P01）。 */
data class ShareRow(
    val key: String,
    val name: String,
    val subtitle: UiText,
    val target: PushTarget?,
    val group: ShareGroup,
    val enabled: Boolean,
    val ownApp: Boolean,
    val pushing: Boolean = false,
    val mirroring: Boolean = false,
    /** 只能推送音乐（音箱遇到照片 / 视频，置灰）。 */
    val audioOnly: Boolean = false,
)

/** 会盖在分享页上的确认（P02 / S07）。 */
sealed interface ShareDialog {
    data class Confirm(val row: ShareRow) : ShareDialog
    data class Replace(val row: ShareRow) : ShareDialog
    data class Switch(val row: ShareRow) : ShareDialog
    data class StopMirror(val row: ShareRow, val connecting: Boolean) : ShareDialog
    data object EditUrl : ShareDialog
}

/** 交给分享页 Activity 执行的一次性动作。 */
sealed interface ShareEffect {
    data object Finish : ShareEffect
    data class Toast(val text: UiText) : ShareEffect
    data class Snackbar(val text: UiText) : ShareEffect
    /** 在本应用自己的任务里打开（遥控页 / 设备列表 + 镜像横幅 / 某台设备的内容面板）。 */
    data class OpenApp(val remote: Boolean = false, val mirrorHint: Boolean = false, val pushUdn: String? = null) : ShareEffect
}

/**
 * 系统分享推送（F14，PRD 8.1.2，交互稿第 14 章）的 ViewModel：解析分享意图 → 到达即检查 → 并行准备文件与找设备 →
 * 冲突 / 首次确认 → 交给 F13 推送引擎（[DmcController]）与前台服务。选完设备之后的一切复用 F13。
 * 日志不记录文件名、地址、网址、来源包名。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val staging: ShareStaging,
    private val dmc: DmcController,
    private val renderers: DlnaRendererStore,
    private val discovery: DeviceDiscovery,
    network: NetworkMonitor,
    private val mirror: SenderController,
    private val settingsRepo: SettingsRepository,
    private val directShare: DirectShareShortcuts,
) : ViewModel() {
    private val _ui = MutableStateFlow(ShareUi())
    val ui: StateFlow<ShareUi> = _ui.asStateFlow()

    private val _dialog = MutableStateFlow<ShareDialog?>(null)
    val dialog: StateFlow<ShareDialog?> = _dialog.asStateFlow()

    private val _effects = MutableSharedFlow<ShareEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<ShareEffect> = _effects

    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lan: StateFlow<LanInfo?> = network.lan
    val session: StateFlow<PushSessionState?> = dmc.session
    val mirrorState: StateFlow<SenderState> = mirror.state
    val searching: StateFlow<Boolean> = discovery.searching
    val searchStartedAt: Long get() = discovery.startedAt

    /** 当前批次的准备进度（没有批次时为空）。 */
    val progress: StateFlow<List<ItemProgress>> = _ui.flatMapLatest { u -> staging.find(u.batchId)?.progress ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 视频是否走转交授权（第一次使用说明的措辞跟随它）。 */
    val transferVideo: Boolean get() = ShareReadPolicy.TRANSFER_VIDEO_BY_DEFAULT || debugTransfer()

    /** 长辈模式：分享页只列家人设置的电视和已经确认过的电视（用户拍板）。 */
    val elder: Boolean get() = settings.value?.elderModeEnabled == true

    /** 本机从没推送成功过（S01 一次性说明）。 */
    val neverPushed: Boolean get() = renderers.records.value.none { it.lastUsedAt > 0 }

    val rows: StateFlow<List<ShareRow>> = combine(
        discovery.devices, renderers.records, dmc.session, mirror.state, combine(settingsRepo.settings, _ui) { s, u -> s to u },
    ) { devices, records, push, mirrorSt, (s, u) ->
        buildRows(devices, records.associate { it.udn to it.lastUsedAt }, records.filter { it.confirmedAt != null }.map { it.udn }.toSet(), push?.takeIf { it.isActive }, mirrorSt, s, u.content)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var directJob: Job? = null
    private var handled = false
    private var directClickedAt = 0L

    init {
        discovery.start(this)
        // 推送开始播放 → S03（照片：电视已取走）
        viewModelScope.launch {
            combine(_ui, dmc.session) { u, s -> u to s }.collect { (u, s) ->
                if (u.stage == ShareStage.STARTING && s != null && s.sessionId == u.sessionId && s.ended == null) {
                    val p = s.engine.phase
                    if (p == PushPhase.PLAYING || p == PushPhase.PAUSED || (p == PushPhase.SHOWING && s.current?.served == true)) {
                        _ui.update { it.copy(stage = ShareStage.STARTED) }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 意图

    /** 首次进入（重建时不重复处理）。 */
    fun handleOnce(intent: Intent) {
        if (handled) return
        handled = true
        handle(intent, replacing = false)
    }

    /** 分享页已打开时又来了一次分享：用新内容替换旧内容（交互稿 S07“再次分享”）。 */
    fun handleNew(intent: Intent) {
        handled = true
        handle(intent, replacing = true)
    }

    private fun handle(intent: Intent, replacing: Boolean) {
        val prev = _ui.value
        // 还没开始推送的旧批次：停止复制并删除
        if (prev.sessionId == null) staging.find(prev.batchId)?.let(staging::cancel)
        directJob?.cancel()
        _dialog.value = null
        _ui.value = ShareUi()
        if (replacing) _effects.tryEmit(ShareEffect.Snackbar(uiText(R.string.share_replaced_snack)))
        val streams = streamsOf(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val type = intent.type
        // 快捷方式 ID 是本机随机令牌；解析不出（伪造 / 已作废）就当普通分享（PM P1-3）
        val shortcutUdn = directShare.udnForShortcut(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        directClickedAt = System.currentTimeMillis()
        val entry = when {
            shortcutUdn != null -> "DIRECT_SHARE"
            else -> "SHARE_SHEET"
        }
        WscLog.i(TAG, "share in: entry=$entry streams=${streams.size} text=${text != null}")
        viewModelScope.launch {
            when {
                streams.isNotEmpty() -> checkFiles(streams, type, shortcutUdn)
                text != null -> checkText(text, shortcutUdn)
                else -> _ui.update { it.copy(stage = ShareStage.BLOCKED, block = ShareBlock.NothingSupported) }
            }
        }
    }

    private fun streamsOf(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
        }
        if (out.isEmpty()) {
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
        }
        return out.toList()
    }

    /** 到达即检查（≤2 秒）：逐项试读并按真实类型过滤（PRD 8.1.2 C/D）。 */
    private suspend fun checkFiles(all: List<Uri>, intentType: String?, shortcutUdn: String?) {
        val truncated = all.size > ShareLimits.MAX_ITEMS
        val uris = all.take(ShareLimits.MAX_ITEMS)
        val transfer = transferVideo
        val result = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + ShareLimits.CHECK_BUDGET_MS
            val items = mutableListOf<SharedItem>()
            var ignored = 0
            var unreadable = 0
            var noPermission = false
            for (u in uris) {
                val inBudget = System.currentTimeMillis() < deadline
                // 单项也设上限：只存在云端的文件，提供方可能先下载再返回（PM P2-4）；超时按“大小未知”走复制
                val probe = if (inBudget) withTimeoutOrNull(ShareLimits.PROBE_TIMEOUT_MS) { kotlinx.coroutines.runInterruptible(Dispatchers.IO) { staging.probe(u) } } else null
                when (probe) {
                    is ShareStaging.Probe.Unreadable -> {
                        val mime = runCatching { context.contentResolver.getType(u) }.getOrNull()
                        if (ShareClassifier.classify(mime, intentType, u.lastPathSegment) == null && mime != null) {
                            ignored++
                        } else {
                            unreadable++
                            if (probe.reason == UnreadableReason.NO_PERMISSION) noPermission = true
                        }
                    }
                    else -> {
                        val ok = probe as? ShareStaging.Probe.Ok
                        val providerMime = ok?.providerMime ?: runCatching { context.contentResolver.getType(u) }.getOrNull()
                        val name = ok?.displayName ?: u.lastPathSegment
                        val kind = ShareClassifier.classify(providerMime, intentType, name)
                        if (kind == null) {
                            ignored++
                            continue
                        }
                        val size = ok?.sizeBytes
                        val seekable = ok?.seekable ?: false
                        val mode = ShareReadPolicy.decide(kind, u.scheme, size, seekable, staging.sourceNeedsCopy(u), transfer)
                        items += SharedItem(items.size, u, kind, ShareClassifier.mimeFor(kind, providerMime, name), ok?.displayName, size, seekable, mode)
                    }
                }
            }
            ShareContent(items, null, ignored, unreadable, if (noPermission) UnreadableReason.NO_PERMISSION else UnreadableReason.GENERIC, truncated)
        }
        WscLog.i(
            TAG,
            "share check: accepted=${result.items.size} ignored=${result.ignored} unreadable=${result.unreadable}(${result.unreadableReason}) " +
                "kinds=${result.kinds.distinct()} copy=${result.items.count { it.mode == ReadMode.COPY }} truncated=$truncated",
        )
        if (result.items.isEmpty()) {
            val block = if (result.unreadable > 0) ShareBlock.Unreadable(result.unreadableReason) else ShareBlock.NothingSupported
            _ui.update { it.copy(stage = ShareStage.BLOCKED, block = block, content = result) }
            return
        }
        // 空间按“待复制总大小 + 余量”在复制之前判断（交互稿 S05 / Q15-C）
        val plan = SpacePlanner.plan(result.items.map { if (it.mode == ReadMode.COPY) (it.sizeBytes ?: 0L) else 0L }, staging.availableBytes())
        if (!plan.fitsAll) {
            WscLog.w(TAG, "share: not enough space (fit ${plan.fitCount}/${plan.total})")
            _ui.update { it.copy(stage = ShareStage.BLOCKED, block = ShareBlock.NoSpace(plan), content = result) }
            return
        }
        beginStaging(result, shortcutUdn)
    }

    private fun beginStaging(content: ShareContent, shortcutUdn: String?) {
        val batch = staging.start(content.items)
        _ui.update { it.copy(content = content, batchId = batch.id, block = null, stage = ShareStage.PICK, directUdn = shortcutUdn) }
        if (shortcutUdn != null) startDirect(shortcutUdn)
    }

    private fun checkText(text: String, shortcutUdn: String?) {
        val url = ShareText.firstHttpUrl(text)
        if (url == null) {
            _ui.update { it.copy(stage = ShareStage.BLOCKED, block = ShareBlock.NoUrl) }
            return
        }
        val kind = ShareText.urlKind(url)
        WscLog.i(TAG, "share text: url kind=$kind")
        _ui.update { it.copy(content = ShareContent(url = url), stage = if (kind == UrlKind.WEB_PAGE) ShareStage.WEB_URL else ShareStage.PICK, directUdn = shortcutUdn) }
        if (kind == UrlKind.MEDIA && shortcutUdn != null) startDirect(shortcutUdn)
    }

    // ------------------------------------------------------------------ 结果卡上的选择

    /** S05 ⑧“只推送前 n 项”。 */
    fun onlyFirst() {
        val u = _ui.value
        val plan = (u.block as? ShareBlock.NoSpace)?.plan ?: return
        val content = u.content.copy(items = u.content.items.take(plan.fitCount))
        WscLog.i(TAG, "share: only first ${plan.fitCount} item(s)")
        beginStaging(content, null)
    }

    /** S05 ⑦“在本应用里重新选”：保留设备列表，点电视后打开本应用的内容面板。 */
    fun reselectInApp() = _ui.update { it.copy(stage = ShareStage.PICK, block = null, reselect = true) }

    /** S04 [仍然推送]。 */
    fun pushWebAnyway() {
        WscLog.i(TAG, "web url: push anyway")
        _ui.update { it.copy(stage = ShareStage.PICK, webAccepted = true) }
        _ui.value.directUdn?.let(::startDirect)
    }

    fun useMirror() {
        WscLog.i(TAG, "share: use mirror instead")
        _effects.tryEmit(ShareEffect.OpenApp(mirrorHint = true))
    }

    fun editUrl() {
        _dialog.value = ShareDialog.EditUrl
    }

    fun setUrl(url: String) {
        _dialog.value = null
        val u = url.trim()
        if (!PushCopy.isPushableUrl(u)) return
        _ui.update { it.copy(content = it.content.copy(url = u)) }
        // 推送失败卡上的 [修改网址]：改完直接在同一台电视上重推（PM P1-7）
        val cur = _ui.value
        val s = dmc.session.value
        if (cur.stage == ShareStage.STARTING && s != null && s.sessionId == cur.sessionId) {
            if (s.isActive) {
                dmc.replace(listOf(PushInput.Remote(u)), PushSourceKind.SHARE)
            } else {
                dmc.dismiss()
                val id = dmc.start(s.target, listOf(PushInput.Remote(u)), PushSourceKind.SHARE)
                MediaPushService.start(context, id)
                _ui.update { it.copy(sessionId = id) }
            }
            WscLog.i(TAG, "share: url edited and re-pushed")
        }
    }

    fun dismissIntro() = viewModelScope.launch { settingsRepo.setShareIntroDismissed() }

    fun restartDiscovery() = discovery.restart(this)

    // ------------------------------------------------------------------ Direct Share（S08）

    private fun startDirect(udn: String) {
        val found = renderers.find(udn)?.name
        val name: Any = found ?: uiText(R.string.share_tv)
        _ui.update { it.copy(stage = ShareStage.DIRECT_FINDING, directName = found) }
        directJob?.cancel()
        directJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            // 5 秒从点击时开始算（检查文件与找设备并行，PM P2-9）
            val budget = (DIRECT_TIMEOUT_MS - (started - directClickedAt)).coerceIn(MIN_DIRECT_MS, DIRECT_TIMEOUT_MS)
            val row = withTimeoutOrNull(budget) { rows.first { list -> list.any { it.enabled && it.target?.udn == udn } }.first { it.enabled && it.target?.udn == udn } }
            if (_ui.value.stage != ShareStage.DIRECT_FINDING) return@launch
            if (row == null) {
                WscLog.i(TAG, "direct share: device not found in ${System.currentTimeMillis() - started}ms, fallback to list")
                // 找到了但置灰（音箱遇到照片 / 视频）：说清原因，而不是“没找到”（PM P2-12）
                val disabled = rows.value.firstOrNull { it.target?.udn == udn && !it.enabled }
                val text = if (disabled != null && disabled.audioOnly) ShareCopy.directAudioOnly(name) else ShareCopy.directMissed(name)
                _ui.update { it.copy(stage = ShareStage.PICK, directMissed = text) }
            } else {
                WscLog.i(TAG, "direct share: found in ${System.currentTimeMillis() - started}ms")
                _ui.update { it.copy(stage = ShareStage.PICK) }
                select(row)
            }
        }
    }

    /** 紧凑面板“选其他电视”。 */
    fun cancelDirect() {
        directJob?.cancel()
        _ui.update { it.copy(stage = ShareStage.PICK) }
    }

    // ------------------------------------------------------------------ 选设备（S01 → P02 / S07 → S02）

    fun select(row: ShareRow) {
        val target = row.target ?: return
        if (!row.enabled) return
        if (_ui.value.reselect) {
            WscLog.i(TAG, "share: reselect in app")
            _effects.tryEmit(ShareEffect.OpenApp(pushUdn = target.udn))
            return
        }
        // 长辈模式不允许对新电视做首次推送（列表里本来就不显示，这里再兜一次）
        if (elder && dmc.needsConfirmation(target.udn)) return
        val cur = dmc.session.value?.takeIf { it.isActive }
        val m = mirror.state.value
        _dialog.value = when {
            cur != null && cur.target.udn == target.udn -> ShareDialog.Replace(row)
            cur != null -> ShareDialog.Switch(row)
            m !is SenderState.Idle && !m.isTerminal -> ShareDialog.StopMirror(row, connecting = m !is SenderState.Casting && m !is SenderState.Reconnecting)
            dmc.needsConfirmation(target.udn) -> ShareDialog.Confirm(row)
            else -> null
        }
        if (_dialog.value == null) go(row, replace = false)
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun confirmDialog() {
        when (val d = _dialog.value) {
            is ShareDialog.Confirm -> {
                dmc.confirm(d.row.target!!)
                _dialog.value = null
                go(d.row, replace = false)
            }
            is ShareDialog.Replace -> {
                WscLog.i(TAG, "conflict: replace")
                _dialog.value = null
                go(d.row, replace = true)
            }
            is ShareDialog.Switch -> {
                WscLog.i(TAG, "conflict: switch device")
                dmc.stop()
                dmc.dismiss()
                _dialog.value = null
                afterConflict(d.row)
            }
            is ShareDialog.StopMirror -> {
                WscLog.i(TAG, "conflict: stop mirroring")
                if (d.connecting) mirror.cancel() else mirror.stop()
                _dialog.value = null
                afterConflict(d.row)
            }
            ShareDialog.EditUrl, null -> _dialog.value = null
        }
    }

    private fun afterConflict(row: ShareRow) {
        if (dmc.needsConfirmation(row.target!!.udn)) _dialog.value = ShareDialog.Confirm(row) else go(row, replace = false)
    }

    /** 开始推送：从这一刻起推送由前台服务持有，关闭分享页不会取消（交互稿 S01“点一行 = 决定推送”）。 */
    private fun go(row: ShareRow, replace: Boolean) {
        val target = row.target ?: return
        var u = _ui.value
        var batch = staging.find(u.batchId)
        // 上一次推送失败 / 换设备后批次已被清理：用原来的分享项重新准备（原文件的授权仍挂在分享页上，PM P1-1）
        if (batch == null && u.content.url == null && u.content.items.isNotEmpty()) {
            batch = staging.start(u.content.items)
            _ui.update { it.copy(batchId = batch.id) }
            u = _ui.value
        }
        val inputs: List<PushInput>
        val grants: List<Uri>
        val url = u.content.url
        if (url != null) {
            inputs = listOf(PushInput.Remote(url))
            grants = emptyList()
        } else {
            batch ?: return
            val b = batch
            val usable = b.items.indices.filter { i -> i < b.limit && b.progress.value[i].phase.let { it != ItemPhase.FAILED && it != ItemPhase.SKIPPED } }
            if (usable.isEmpty()) {
                WscLog.w(TAG, "share: nothing left to push (all items failed)")
                _ui.update { it.copy(stage = ShareStage.BLOCKED, block = ShareBlock.PrepareFailed) }
                return
            }
            inputs = usable.map { i -> PushInput.Local(b.pushUris[i], b.items[i].mime, b.items[i].displayName) }
            // 所有原文件的读授权都转交给前台服务：转交的视频直接读原文件；复制项在分享页关闭后也能继续复制
            grants = usable.map { b.items[it].source }
        }
        val cur = dmc.session.value?.takeIf { it.isActive }
        val sessionId = if (replace && cur != null && cur.target.udn == target.udn) {
            dmc.replace(inputs, PushSourceKind.SHARE)
            cur.sessionId
        } else {
            dmc.start(target, inputs, PushSourceKind.SHARE)
        }
        MediaPushService.start(context, sessionId, grants)
        batch?.let { staging.bind(it, sessionId) }
        WscLog.i(TAG, "share push: ${inputs.size} item(s), replace=$replace, ownApp=${target.description.isOwnApp}")
        _ui.update { it.copy(stage = ShareStage.STARTING, target = target, sessionId = sessionId) }
    }

    /** S02 返回箭头 = 换一台（已复制的进度保留）。 */
    fun changeDevice() {
        staging.find(_ui.value.batchId)?.let(staging::unbind)
        dmc.stop()
        dmc.dismiss()
        WscLog.i(TAG, "share: change device")
        _ui.update { it.copy(stage = ShareStage.PICK, sessionId = null, target = null) }
    }

    /** S02 [取消推送]：停止复制并删除缓存，向电视发停止，关闭分享页。 */
    fun cancelPush() {
        val u = _ui.value
        staging.find(u.batchId)?.let { staging.unbind(it); staging.cancel(it) }
        if (u.sessionId != null && dmc.session.value?.sessionId == u.sessionId) {
            dmc.stop()
            dmc.dismiss()
        }
        WscLog.i(TAG, "share: push cancelled")
        _ui.update { it.copy(sessionId = null) }
        // 长辈模式不用会自己消失的 Toast（PRD 8.1.4 E）
        if (!elder) _effects.tryEmit(ShareEffect.Toast(uiText(R.string.share_cancelled_toast)))
        _effects.tryEmit(ShareEffect.Finish)
    }

    fun retry() {
        val s = dmc.session.value ?: return
        if (s.ended == null) dmc.select(s.engine.index)
    }

    fun acceptTranscode(videoOnly: Boolean, auto: Boolean) = dmc.acceptTranscode(videoOnly, auto)
    fun tryOriginal() = dmc.tryOriginal()
    fun dismissTranscodeOffer() = dmc.dismissTranscodeOffer()
    fun offerTranscode() = dmc.session.value?.let { dmc.offerTranscodeFor(it.engine.index) }

    /** 当前项复制失败的原因（失败卡用具体文案，PM P1-8）；不是分享副本或没有失败时为 null。 */
    fun copyFailureFor(s: PushSessionState): CopyFailure? {
        val b = staging.find(_ui.value.batchId) ?: return null
        val i = b.pushUris.indexOf(s.current?.uri ?: return null)
        if (i < 0) return null
        return b.progress.value.getOrNull(i)?.failure
    }

    fun openRemote() {
        _effects.tryEmit(ShareEffect.OpenApp(remote = true))
    }

    override fun onCleared() {
        // 还没决定推送就关闭 = 放弃：停止复制、删除已复制的文件（交互稿 S01“关闭”）
        val u = _ui.value
        if (u.sessionId == null) staging.find(u.batchId)?.let(staging::cancel)
        discovery.stop(this)
        super.onCleared()
    }

    // ------------------------------------------------------------------ 设备行

    private fun buildRows(
        devices: List<DiscoveredDevice>,
        pushedAt: Map<String, Long>,
        confirmed: Set<String>,
        push: PushSessionState?,
        mirrorSt: SenderState,
        s: AppSettings,
        content: ShareContent,
    ): List<ShareRow> {
        val family = if (s.elderModeEnabled) s.elderTargets.mapNotNull { it.udn }.toSet() else emptySet()
        val familyIds = if (s.elderModeEnabled) s.elderTargets.mapNotNull { it.wscId }.toSet() else emptySet()
        val mirroringId = when (mirrorSt) {
            is SenderState.Casting -> mirrorSt.target.deviceId
            is SenderState.Reconnecting -> mirrorSt.target.deviceId
            is SenderState.Starting -> mirrorSt.target.deviceId
            is SenderState.Connecting -> mirrorSt.target.deviceId
            is SenderState.AwaitingPin -> mirrorSt.target.deviceId
            is SenderState.AwaitingTvConfirm -> mirrorSt.target.deviceId
            is SenderState.ReadyToCapture -> mirrorSt.target.deviceId
            else -> null
        }
        val needsVisual = content.url != null || content.items.any { it.kind != MediaKind.AUDIO }
        val rows = devices.map { d ->
            val ref = d.dlna
            val ownApp = d.protocol == DeviceProtocol.WSC
            val target = ref?.let {
                PushTarget(
                    description = it.description, server = it.server, sink = it.sink,
                    discoveryPath = d.sources.firstOrNull()?.name,
                    discoveryMs = if (discovery.startedAt > 0) (d.firstSeenAt - discovery.startedAt).coerceAtLeast(0) else null,
                )
            }
            val pushing = ref != null && push?.target?.udn == ref.udn
            val mirroring = mirroringId != null && mirroringId == d.deviceId
            val last = ref?.let { pushedAt[it.udn] }?.takeIf { it > 0 }
            val audioBlocked = ref != null && ref.audioOnly && needsVisual
            val enabled = ref != null && !audioBlocked
            val group = when {
                ref == null -> ShareGroup.DISABLED
                audioBlocked -> ShareGroup.DISABLED
                ref.udn in family || d.deviceId in familyIds -> ShareGroup.FAMILY
                mirroring -> ShareGroup.RECENT
                last != null -> ShareGroup.RECENT
                ownApp -> ShareGroup.OWN
                else -> ShareGroup.DLNA
            }
            val subtitle: UiText = when {
                ref == null -> uiText(R.string.share_needs_dlna)
                audioBlocked -> uiText(R.string.share_audio_only)
                pushing -> uiText(R.string.share_row_pushing, PushCopy.bannerStatus(push!!))
                mirroring -> uiText(R.string.share_row_mirroring)
                last != null -> uiText(if (ownApp) R.string.share_row_last_own else R.string.share_row_last_dlna, com.benchan.wireless.screencasting.feature.sender.ui.relativeTime(last))
                ownApp -> uiText(if (d.fingerprintHex != null) R.string.share_row_trusted else R.string.share_row_can_receive)
                // 厂商 / 型号是设备自报的原文，不翻译
                else -> listOfNotNull(ref.description.manufacturer, ref.description.modelName).joinToString(" ")
                    .takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: uiText(R.string.share_row_dlna_unencrypted)
            }
            val elderSubtitle = when {
                ref == null -> uiText(R.string.share_elder_needs_setup)
                audioBlocked -> uiText(R.string.share_elder_audio_sub)
                pushing -> uiText(R.string.share_elder_row_pushing)
                else -> uiText(R.string.share_elder_ok_sub)
            }
            ShareRow(d.deviceId, d.name, if (s.elderModeEnabled) elderSubtitle else subtitle, target, group, enabled, ownApp, pushing, mirroring, audioOnly = audioBlocked)
        }.filter { r ->
            // 长辈模式：只列家人设置的电视和已经确认过的电视，不对新电视做首次推送（用户拍板）
            !s.elderModeEnabled || r.group == ShareGroup.FAMILY || (r.target?.udn?.let { it in confirmed } == true)
        }
        // 正在镜像的那台排最前（S07），其余按组、最近推送时间
        return rows.sortedWith(
            compareBy<ShareRow>({ it.group.ordinal }, { !it.mirroring }, { -(it.target?.udn?.let { u -> pushedAt[u] } ?: 0L) }, { it.name }),
        )
    }

    /** 仅可调试构建：`adb shell setprop debug.wsc.share_transfer 1` 让视频走转交授权（真机验证 Q20 用；默认复制）。 */
    @SuppressLint("PrivateApi")
    private fun debugTransfer(): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        return runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.wsc.share_transfer") == "1"
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "Share"
        const val DIRECT_TIMEOUT_MS = 5_000L
        const val MIN_DIRECT_MS = 1_500L
    }
}
