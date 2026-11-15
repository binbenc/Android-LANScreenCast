package com.benchan.wireless.screencasting.protocol.dlna.dmc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.benchan.wireless.screencasting.core.common.Ids
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.core.data.dlna.TranscodeSpeedStore
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.data.report.PushReport
import com.benchan.wireless.screencasting.core.data.report.SessionRecord
import com.benchan.wireless.screencasting.core.data.report.SessionReportStore
import com.benchan.wireless.screencasting.core.data.report.TranscodeReport
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import com.benchan.wireless.screencasting.protocol.upnp.DlnaParams
import com.benchan.wireless.screencasting.protocol.upnp.FormatCheck
import com.benchan.wireless.screencasting.protocol.upnp.LastChangeParser
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MediaMime
import com.benchan.wireless.screencasting.protocol.upnp.SinkMatcher
import com.benchan.wireless.screencasting.protocol.upnp.SinkSupport
import com.benchan.wireless.screencasting.protocol.upnp.dmc.HttpRendererPort
import com.benchan.wireless.screencasting.protocol.upnp.dmc.ListFailureAction
import com.benchan.wireless.screencasting.protocol.upnp.dmc.ListFailurePolicy
import com.benchan.wireless.screencasting.protocol.upnp.dmc.MediaPreparer
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PlaylistEntry
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PrepareResult
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PreparedMedia
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEngine
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEvent
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import com.benchan.wireless.screencasting.protocol.upnp.dmc.RendererQuirks
import com.benchan.wireless.screencasting.protocol.upnp.dmc.SyncMode
import com.benchan.wireless.screencasting.protocol.upnp.http.UpnpHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DLNA 推送（F13）的会话编排（技术方案 3.13.5）：文件服务、照片 JPEG、视频转换、推送引擎、GENA、网络变化、报告。
 * 同一时间只有一个推送会话（PRD 8.1.1 A）；和镜像互斥由界面层保证。
 * 所有状态变化在单线程调度器上进行。日志不记录文件名、路径、网址、令牌（PRD 8.1.1 G）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DmcController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: NetworkMonitor,
    private val reports: SessionReportStore,
    private val renderers: DlnaRendererStore,
    private val speed: TranscodeSpeedStore,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val resolver = context.contentResolver
    private val http = UpnpHttpClient(userAgent = userAgent(context))

    private val _session = MutableStateFlow<PushSessionState?>(null)
    /** 当前（或刚结束的）推送会话；null = 没有。 */
    val session: StateFlow<PushSessionState?> = _session.asStateFlow()

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<PushEvent>(extraBufferCapacity = 8)
    /** 引擎的一次性事件（“电视没有响应”“不支持拖动”等，界面用 Snackbar 提示，第 1 轮 PM P1-4）。 */
    val events: kotlinx.coroutines.flow.SharedFlow<PushEvent> = _events

    private val _thermalPaused = MutableStateFlow(false)
    private val _transcoding = MutableStateFlow(false)
    /** 正在转换视频（前台服务据此在 Android 15+ 叠加 mediaProcessing 类型）。 */
    val transcoding: StateFlow<Boolean> = _transcoding.asStateFlow()

    private var s: Session? = null

    /** 一次推送会话的内部资源。 */
    private inner class Session(
        val id: String,
        val target: PushTarget,
        val server: MediaShareServer,
        val engine: PushEngine,
        val localIp: String,
        var source: PushSourceKind,
        val dir: File,
    ) {
        var totalItems = 0
        var networkLossJob: Job? = null
        var inputs: List<PushInput> = emptyList()
        var items: List<PushItemInfo> = emptyList()
        val probes = ConcurrentHashMap<Int, MediaProbe>()
        val transcoded = ConcurrentHashMap<Int, File>()
        val photos = ConcurrentHashMap<Int, PhotoTranscoder.Result>()
        val originalChosen = HashSet<Int>()
        val offered = HashSet<Int>()
        var autoTranscode = false
        var transcodeJob: Job? = null
        var transcodeReport: TranscodeReport? = null
        var genaSid: String? = null
        var genaJob: Job? = null
        val jobs = mutableListOf<Job>()
        var startedAt = System.currentTimeMillis()
        var finalized = false
        var resumeAt: Long? = null
        var lastIndex = -1
        var currentServed = false
        var slowJob: Job? = null
        var skipJob: Job? = null
        val failedItems = HashSet<Int>()
        var skipped: PushSkipNotice? = null
        var photoSlow = false
        var succeeded = false
    }

    /**
     * F14：分享进来的文件在推送开始时可能还在复制到缓存（第 1 项准备好就开始，其余边放边准备，交互稿 Q15-B）。
     * 设置后，每个本地项在准备前先等它就绪；返回 false = 这一项读取 / 复制失败。
     */
    fun interface PushInputGate {
        suspend fun await(uri: Uri): Boolean
    }

    @Volatile
    var inputGate: PushInputGate? = null

    /** 遥控页是否在前台（结束时决定是否发“推送已结束”通知）。 */
    @Volatile
    var remoteVisible: Boolean = false
        private set

    init {
        // App 启动时清理上次异常退出留下的转换文件 / 照片缓存（PRD F2）
        DmcCache.cleanup(context)
        registerThermalListener()
    }

    // ------------------------------------------------------------------ 确认（D13-3）

    fun needsConfirmation(udn: String): Boolean = !renderers.isConfirmed(udn)

    /** 记住确认（写文件放到后台线程，界面线程只更新内存里的状态）。 */
    fun confirm(target: PushTarget) {
        val d = target.description
        scope.launch(Dispatchers.IO) { renderers.confirm(d.udn, d.displayName, d.manufacturer, d.modelName, d.location) }
    }

    // ------------------------------------------------------------------ 会话

    /**
     * 开始推送（如果已有会话，先结束它——界面应已征得用户同意）。
     * 同步返回本次会话 ID：前台服务据此只在“本次”会话结束时停止（第 1 轮 PM P0-1）。
     */
    fun start(target: PushTarget, inputs: List<PushInput>, source: PushSourceKind, startIndex: Int = 0, resumePositionMs: Long? = null): String {
        val id = Ids.newSessionId()
        scope.launch { startInternal(id, target, inputs, source, startIndex, resumePositionMs) }
        return id
    }

    private suspend fun startInternal(id: String, target: PushTarget, inputs: List<PushInput>, source: PushSourceKind, startIndex: Int, resumePositionMs: Long?) {
        if (inputs.isEmpty()) return
        s?.let { if (!it.finalized) endSession(it, PushEndReason.USER_STOPPED) }
        val localIp = network.lan.value?.ip
        if (localIp == null) {
            WscLog.w(TAG, "no LAN, cannot push")
            return startFailed(id, target, inputs, source, PushError.NO_NETWORK)
        }
        val server = MediaShareServer(allowedRemote = target.host)
        if (runCatching { server.start() }.onFailure { WscLog.w(TAG, "media server failed", it) }.isFailure) {
            return startFailed(id, target, inputs, source, PushError.UNREACHABLE)
        }
        val rec = renderers.find(target.udn)
        val engine = PushEngine(
            port = HttpRendererPort(http, target.description),
            initialQuirks = RendererQuirks.fromMap(rec?.quirks),
            hasRenderingControl = target.description.renderingControl != null,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            now = { System.nanoTime() / 1_000_000 },
            // 本应用接收端第一次被推送会弹“允许”，SetAVTransportURI 挂起到对方确认（第 1 轮 EMU-1）
            consentAware = target.description.isOwnApp,
        )
        val dir = File(DmcCache.dir(context), id.take(8)).apply { mkdirs() }
        val session = Session(id, target, server, engine, localIp, source, dir)
        session.resumeAt = resumePositionMs
        s = session
        renderers.recordUse(target.udn, target.name, target.description.manufacturer, target.description.modelName, target.description.location, wscId = target.description.wscId)
        setInputs(session, inputs)
        session.totalItems = inputs.size
        observe(session)
        WscLog.i(TAG, "push start: ${session.items.size} item(s), kinds=${session.items.map { it.kind }.distinct()}, source=$source")
        engine.start(session.items.map { PlaylistEntry(it.kind, it.remote) }, preparer(session), startIndex)
    }

    /** 没能开始（没有局域网 / 文件服务起不来）：发布一个已结束的会话给界面，并写失败报告（第 1 轮 PM P1-3）。 */
    private fun startFailed(id: String, target: PushTarget, inputs: List<PushInput>, source: PushSourceKind, error: PushError) {
        val now = System.currentTimeMillis()
        val info = PushEndInfo(
            reason = PushEndReason.START_FAILED, lastIndex = 0, lastPositionMs = null, canResume = false, durationMs = 0,
            videoCount = 0, photoCount = 0, audioCount = 0, urlCount = inputs.count { it is PushInput.Remote }, failure = error,
        )
        s = null
        _session.value = PushSessionState(
            sessionId = id, target = target, items = emptyList(),
            engine = com.benchan.wireless.screencasting.protocol.upnp.dmc.PushState(phase = PushPhase.FAILED, failure = com.benchan.wireless.screencasting.protocol.upnp.dmc.PushFailure(error)),
            startedAt = now, ended = info, ownApp = target.description.isOwnApp,
        )
        runCatching {
            reports.add(
                SessionRecord(
                    sessionId = id, protocol = CastProtocol.DLNA_PUSH, role = "SENDER", peerName = target.name, startedAt = now, endedAt = now,
                    success = false, endReason = "START_FAILED_${error.name}",
                    androidVersion = Build.VERSION.RELEASE.orEmpty(), deviceModel = Build.MODEL.orEmpty(),
                    push = PushReport(contentType = "UNKNOWN", source = source.name, itemCount = inputs.size, tvManufacturer = target.description.manufacturer, tvModel = target.description.modelName, tvServer = target.server),
                ),
            )
        }
    }

    /** 换内容：同一台电视上替换（PRD 8.1.1 D）。 */
    fun replace(inputs: List<PushInput>, source: PushSourceKind? = null) = scope.launch {
        val session = s?.takeIf { !it.finalized } ?: return@launch
        if (inputs.isEmpty()) return@launch
        source?.let { session.source = it }
        session.totalItems += inputs.size
        cancelTranscode(session)
        session.transcoded.values.forEach { it.delete() }
        session.transcoded.clear()
        session.photos.values.forEach { it.file.delete() }
        session.photos.clear()
        session.probes.clear()
        session.originalChosen.clear()
        session.offered.clear()
        (0 until session.items.size).forEach { session.server.unregister(it) }
        setInputs(session, inputs)
        session.engine.replace(session.items.map { PlaylistEntry(it.kind, it.remote) }, preparer(session))
    }

    fun play() = s?.engine?.play()
    fun pause() = s?.engine?.pause()
    fun togglePlay() = s?.engine?.togglePlay()
    fun seekTo(ms: Long) = s?.engine?.seekTo(ms)
    fun setVolume(v: Int) = s?.engine?.setVolume(v)
    fun adjustVolume(delta: Int) = s?.engine?.adjustVolume(delta)
    fun setMute(m: Boolean) = s?.engine?.setMute(m)
    fun next() = s?.engine?.next()
    fun previous() = s?.engine?.previous()
    fun select(index: Int) = s?.engine?.select(index)
    fun keepAlive() = s?.engine?.keepAlive()
    fun setForeground(visible: Boolean) {
        remoteVisible = visible
        s?.engine?.setForeground(visible)
    }

    /** 结束推送（遥控页 / 通知里的“停止”）。 */
    fun stop() = scope.launch { s?.let { if (!it.finalized) endSession(it, PushEndReason.USER_STOPPED) } }

    /** 结束页“关闭”：清掉已结束的会话状态。 */
    fun dismiss() = scope.launch {
        if (s?.finalized != false) {
            _session.value = null
        }
    }

    /** 交互稿 14-G：重新推送并从上次位置继续（Seek），不自动恢复。返回新会话 ID（没有可继续的会话时为 null）。 */
    fun resumeLast(): String? {
        val last = s?.takeIf { it.finalized } ?: return null
        val info = _session.value?.ended ?: return null
        return start(last.target, last.inputs, last.source, info.lastIndex, info.lastPositionMs)
    }

    // ------------------------------------------------------------------ 转换后推送（F2）

    /** 用户在确认卡点了“转换后推送”（[videoOnly]：只转换画面，无声音）。 */
    fun acceptTranscode(videoOnly: Boolean = false, autoForList: Boolean = true) = scope.launch {
        val session = s?.takeIf { !it.finalized } ?: return@launch
        val ui = _session.value?.transcode ?: return@launch
        session.autoTranscode = autoForList
        session.transcodeReport = (session.transcodeReport ?: TranscodeReport(offered = true, offeredAt = ui.offeredAt.name)).copy(choice = "TRANSCODE")
        runTranscode(session, ui.index, videoOnly)
    }

    /** “直接尝试原文件”。 */
    fun tryOriginal() = scope.launch {
        val session = s?.takeIf { !it.finalized } ?: return@launch
        val ui = _session.value?.transcode ?: return@launch
        cancelTranscode(session)
        session.originalChosen += ui.index
        session.transcodeReport = (session.transcodeReport ?: TranscodeReport(offered = true, offeredAt = ui.offeredAt.name)).copy(choice = "ORIGINAL")
        setTranscodeUi(null)
        session.engine.reload()
    }

    /** 取消转换：1 秒内停止并删除临时文件，回到推送前的状态（PRD F2）。 */
    fun cancelTranscode() = scope.launch {
        val session = s ?: return@launch
        cancelTranscode(session)
        val ui = _session.value?.transcode
        session.transcodeReport = (session.transcodeReport ?: TranscodeReport(offered = true)).copy(choice = session.transcodeReport?.choice ?: "CANCEL", result = "CANCELLED")
        setTranscodeUi(ui?.copy(phase = TranscodeUi.Phase.OFFER, progress = 0))
    }

    /** 播放列表里失败项的 [转换]（交互稿 P08 → P10）。 */
    fun offerTranscodeFor(index: Int) = scope.launch {
        val session = s?.takeIf { !it.finalized } ?: return@launch
        val item = session.items.getOrNull(index)?.takeIf { it.kind == MediaKind.VIDEO && !it.remote } ?: return@launch
        session.skipJob?.cancel()
        val probe = session.probes[index] ?: withContext(Dispatchers.IO) { probeVideo(item) }?.also { session.probes[index] = it }
        offerTranscode(session, index, probe, TranscodeUi.OfferedAt.AFTER_FAILURE)
    }

    /** 关闭转换提议（P10 下滑 / 点遮罩）。 */
    fun dismissTranscodeOffer() = scope.launch {
        if (_session.value?.transcode?.phase != TranscodeUi.Phase.RUNNING) setTranscodeUi(null)
    }

    /** 列表里“跳过这一项”。 */
    fun skipItem() = scope.launch {
        val session = s ?: return@launch
        cancelTranscode(session)
        setTranscodeUi(null)
        session.engine.next()
    }

    // ------------------------------------------------------------------ 内部

    private fun setInputs(session: Session, inputs: List<PushInput>) {
        session.inputs = inputs
        session.items = inputs.mapIndexed { i, input ->
            when (input) {
                is PushInput.Remote -> {
                    val (mime, kind) = MediaMime.guessForUrl(input.url)
                    PushItemInfo(i, kind, remote = true, title = urlTitle(input.url), mime = mime)
                }
                is PushInput.Local -> {
                    val mime = (resolver.getType(input.uri) ?: input.mimeHint ?: "application/octet-stream").let(MediaMime::normalize)
                    val name = displayTitle(input.displayName ?: ContentUriSource.displayName(resolver, input.uri), mime, i)
                    PushItemInfo(i, MediaMime.kindOf(mime) ?: MediaKind.VIDEO, remote = false, title = name, mime = mime, uri = input.uri)
                }
            }
        }
        publish(session)
    }

    private fun observe(session: Session) {
        session.jobs += scope.launch {
            session.engine.state.collect { publish(session) }
        }
        session.jobs += scope.launch {
            session.engine.events.collect { e ->
                _events.tryEmit(e)
                onEngineEvent(session, e)
            }
        }
        // 手机网络变化（断开 Wi-Fi、换网、IP 变了）→ 结束（PRD 8.1.1 D）
        session.jobs += scope.launch {
            network.lan.drop(1).collect { lan ->
                // 短暂抖动（Wi-Fi 切换瞬间为 null）不算：同一地址 4 秒内恢复就继续（第 1 轮 PM P2-2）
                session.networkLossJob?.cancel()
                if (lan?.ip != session.localIp && !session.finalized) {
                    session.networkLossJob = scope.launch {
                        delay(NETWORK_DEBOUNCE_MS)
                        if (network.lan.value?.ip != session.localIp) endSession(session, PushEndReason.NETWORK_CHANGED)
                    }
                }
            }
        }
        session.server.onReadError = { index -> session.engine.reportError(index, PushError.FILE_UNREADABLE) }
        session.server.onServed = { index ->
            scope.launch {
                if (index == session.engine.state.value.index && !session.currentServed) {
                    session.currentServed = true
                    session.slowJob?.cancel()
                    session.photoSlow = false
                    publish(session)
                }
            }
        }
        session.server.onNotify = { sid, body ->
            if (sid != null && sid == session.genaSid) LastChangeParser.parse(body)?.let { session.engine.onEvent(it) }
        }
    }

    private suspend fun onEngineEvent(session: Session, e: PushEvent) {
        when (e) {
            is PushEvent.Loaded -> {
                val item = session.items.getOrNull(e.index)
                if (!session.succeeded) {
                    // 第一次成功开始播放：记下时间（F14 Direct Share 只放推送成功过的设备，PRD 8.1.2 H）
                    session.succeeded = true
                    val http = session.target.sink?.filter { it.isHttpGet }.orEmpty()
                    renderers.recordSuccess(session.target.udn, audioOnly = http.isNotEmpty() && http.all { it.contentFormat.startsWith("audio/") })
                }
                // 用户选了“直接尝试原文件”且成功：这台电视同类格式以后不再提议（PRD F2）
                if (item?.kind == MediaKind.VIDEO && !item.remote && e.index in session.originalChosen) {
                    session.probes[e.index]?.let { renderers.recordFormat(session.target.udn, it.format.key, ok = true) }
                }
                session.resumeAt?.let { pos ->
                    session.resumeAt = null
                    if (pos > 5_000 && item?.kind != MediaKind.IMAGE) session.engine.seekTo(pos)
                }
                if (session.genaSid == null && session.genaJob == null) startGena(session)
                trimCaches(session, e.index)
            }
            is PushEvent.Failed -> onItemFailed(session, e.index, e.failure.error)
            // 在独立协程里收尾：endSession 会取消本收集协程
            is PushEvent.Ended -> scope.launch { endSession(session, e.reason) }
            else -> Unit
        }
    }

    private suspend fun onItemFailed(session: Session, index: Int, error: PushError) {
        val item = session.items.getOrNull(index) ?: return
        if (error == PushError.NEEDS_TRANSCODE) return // 推送前提议：preparer 已经设置了确认卡
        val localVideo = item.kind == MediaKind.VIDEO && !item.remote && !session.transcoded.containsKey(index)
        val formatError = error == PushError.UNSUPPORTED_FORMAT || error == PushError.START_TIMEOUT
        val probe = if (localVideo && formatError) {
            (session.probes[index] ?: withContext(Dispatchers.IO) { probeVideo(item) }?.also { session.probes[index] = it })
                ?.also { if (ListFailurePolicy.recordsFormatFailure(error)) renderers.recordFormat(session.target.udn, it.format.key, ok = false) }
        } else null
        val action = ListFailurePolicy.decide(error)
        if (session.items.size > 1 && action == ListFailureAction.END_DEVICE_LOST) {
            scope.launch { endSession(session, PushEndReason.DEVICE_LOST) }
            return
        }
        if (session.items.size > 1 && action == ListFailureAction.SKIP) {
            // 列表：某项失败不中断，标为“已跳过”，3 秒后推下一项（交互稿 P08）；视频项可在列表里点 [转换]
            session.failedItems += index
            session.items = session.items.map { if (it.index == index) it.copy(failed = true) else it }
            session.skipped = PushSkipNotice(index, System.currentTimeMillis())
            publish(session)
            session.skipJob?.cancel()
            session.skipJob = scope.launch {
                delay(SKIP_DELAY_MS)
                val st = session.engine.state.value
                if (session.finalized || st.index != index || st.phase != PushPhase.FAILED || _session.value?.transcode != null) return@launch
                if (index + 1 < session.items.size) session.engine.next()
                else endSession(session, ListFailurePolicy.endReasonAfterLast(session.engine.stats.value.loadedCount))
            }
            return
        }
        if (localVideo && formatError) offerTranscode(session, index, probe, TranscodeUi.OfferedAt.AFTER_FAILURE)
    }

    private fun preparer(session: Session) = MediaPreparer { index ->
        withContext(Dispatchers.IO) { prepare(session, index) }
    }

    private suspend fun prepare(session: Session, index: Int): PrepareResult {
        val item = session.items[index]
        val input = session.inputs[index]
        if (input is PushInput.Remote) {
            val host = runCatching { URI(input.url).host }.getOrNull().orEmpty()
            return PrepareResult.Ready(
                PreparedMedia(
                    url = input.url, mime = item.mime, kind = item.kind, title = item.title,
                    protocolInfo = DlnaParams.forRemote(item.mime), remote = true, ownershipKey = input.url,
                ),
            )
        }
        val uri = (input as PushInput.Local).uri
        inputGate?.let { gate -> if (!gate.await(uri)) return PrepareResult.Failed(PushError.FILE_UNREADABLE) }
        return when (item.kind) {
            MediaKind.IMAGE -> preparePhoto(session, index, uri)
            MediaKind.AUDIO -> prepareOriginal(session, index, uri, item.mime ?: "audio/mpeg", MediaKind.AUDIO, probe = null)
            MediaKind.VIDEO -> prepareVideo(session, index, uri, item)
        }
    }

    private suspend fun preparePhoto(session: Session, index: Int, uri: Uri): PrepareResult {
        val photo = session.photos[index] ?: runCatching {
            PhotoTranscoder(resolver).toJpeg(uri, File(session.dir, "p$index.jpg"))
        }.getOrElse {
            WscLog.w(TAG, "photo convert failed: ${it.javaClass.simpleName}")
            // 授权失效（SecurityException / 找不到文件）与照片本身解码失败分开提示（第 1 轮 PM P2-17）
            return PrepareResult.Failed(if (it is SecurityException || it is java.io.FileNotFoundException) PushError.FILE_UNREADABLE else PushError.DECODE_FAILED)
        }.also { session.photos[index] = it }
        val entry = ShareEntry(index, "jpg", "image/jpeg", MediaKind.IMAGE, FileShareSource(photo.file), converted = true)
        session.server.register(entry)
        val profile = DlnaParams.jpegProfile(photo.width, photo.height)
        return PrepareResult.Ready(
            PreparedMedia(
                url = session.server.url(session.localIp, entry), mime = "image/jpeg", kind = MediaKind.IMAGE,
                title = session.items[index].title,
                protocolInfo = DlnaParams.protocolInfo("image/jpeg", DlnaParams.forLocal(MediaKind.IMAGE, true, true, profile)),
                sizeBytes = photo.file.length(), resolution = "${photo.width}x${photo.height}", ownershipKey = session.server.ownershipKey,
            ),
        )
    }

    private suspend fun prepareVideo(session: Session, index: Int, uri: Uri, item: PushItemInfo): PrepareResult {
        session.transcoded[index]?.takeIf { it.exists() }?.let { file ->
            val entry = ShareEntry(index, "mp4", "video/mp4", MediaKind.VIDEO, FileShareSource(file), converted = true)
            session.server.register(entry)
            val probe = session.probes[index]
            return PrepareResult.Ready(
                PreparedMedia(
                    url = session.server.url(session.localIp, entry), mime = "video/mp4", kind = MediaKind.VIDEO, title = item.title,
                    protocolInfo = DlnaParams.protocolInfo("video/mp4", DlnaParams.forLocal(MediaKind.VIDEO, true, true)),
                    sizeBytes = file.length(), durationMs = probe?.durationMs, ownershipKey = session.server.ownershipKey,
                ),
            )
        }
        val probe = session.probes[index] ?: probeVideo(item)?.also { session.probes[index] = it }
        if (index !in session.originalChosen && probe != null) {
            val sink = session.target.sink.orEmpty()
            val verdict = FormatCheck.evaluate(sink, probe.format)
            val rec = renderers.find(session.target.udn)
            val failedBefore = rec?.failedFormats?.contains(probe.format.key) == true
            val okBefore = rec?.okFormats?.contains(probe.format.key) == true
            if ((verdict.suggestTranscode || failedBefore) && !okBefore) {
                if (session.autoTranscode) {
                    scope.launch { runTranscode(session, index, videoOnly = false) }
                } else {
                    offerTranscode(session, index, probe, TranscodeUi.OfferedAt.BEFORE_PUSH, verdict.containerUnsupported, verdict.codecUnsupported, failedBefore)
                }
                return PrepareResult.Deferred(PushError.NEEDS_TRANSCODE)
            }
        }
        return prepareOriginal(session, index, uri, item.mime ?: "video/mp4", MediaKind.VIDEO, probe)
    }

    private fun prepareOriginal(session: Session, index: Int, uri: Uri, mime: String, kind: MediaKind, probe: MediaProbe?): PrepareResult {
        val src = ContentUriSource.probe(resolver, uri) ?: return PrepareResult.Failed(PushError.FILE_UNREADABLE)
        val reported = SinkMatcher.preferredMime(session.target.sink.orEmpty(), mime)
        val entry = ShareEntry(index, MediaMime.extensionFor(reported).takeIf { it != "bin" } ?: MediaMime.extensionFor(mime), reported, kind, src)
        session.server.register(entry)
        session.items = session.items.map { if (it.index == index) it.copy(sizeBytes = src.sizeBytes) else it }
        return PrepareResult.Ready(
            PreparedMedia(
                url = session.server.url(session.localIp, entry), mime = reported, kind = kind, title = session.items[index].title,
                protocolInfo = DlnaParams.protocolInfo(reported, DlnaParams.forLocal(kind, entry.seekable)),
                sizeBytes = src.sizeBytes, durationMs = probe?.durationMs, resolution = probe?.resolution,
                ownershipKey = session.server.ownershipKey,
            ),
        )
    }

    private fun probeVideo(item: PushItemInfo): MediaProbe? = item.uri?.let { VideoProbeReader.probe(context, it, item.mime ?: "video/mp4") }

    private fun offerTranscode(
        session: Session, index: Int, probe: MediaProbe?, at: TranscodeUi.OfferedAt,
        containerUnsupported: Boolean = false, codecUnsupported: Boolean = false, failedBefore: Boolean = false,
    ) {
        session.offered += index
        val report = session.transcodeReport ?: TranscodeReport()
        session.transcodeReport = report.copy(
            offered = true, offeredAt = report.offeredAt ?: at.name,
            sourceContainer = probe?.format?.containerMime, sourceVideoCodec = probe?.format?.videoMime, sourceAudioCodec = probe?.format?.audioMime,
            sourceResolution = probe?.resolutionBucket, hdr = probe?.format?.hdr == true,
        )
        if (probe == null || !probe.videoDecodable) {
            setTranscodeUi(TranscodeUi(index, TranscodeUi.Phase.PHONE_UNSUPPORTED, at))
            return
        }
        val spec = specFor(probe, removeAudio = false)
        val space = VideoTranscoder(context).checkSpace(session.dir, spec, session.items.getOrNull(index)?.sizeBytes)
        val (battery, charging) = battery()
        setTranscodeUi(
            TranscodeUi(
                index = index, phase = TranscodeUi.Phase.OFFER, offeredAt = at,
                estimatedDurationMs = probe.durationMs?.let { (it * speed.factor()).toLong() },
                requiredBytes = space.requiredBytes, availableBytes = space.availableBytes,
                batteryPercent = battery, batteryLow = battery != null && battery < LOW_BATTERY && !charging,
                videoOnlyAvailable = !probe.audioDecodable && probe.format.audioMime != null, hdr = probe.format.hdr,
                containerUnsupported = containerUnsupported, codecUnsupported = codecUnsupported, failedBefore = failedBefore,
                inPlaylist = session.items.size > 1, videoCodec = probe.format.videoMime, audioCodec = probe.format.audioMime,
                durationMs = probe.durationMs,
            ),
        )
    }

    private fun specFor(p: MediaProbe, removeAudio: Boolean) = TranscodeSpec(
        removeAudio = removeAudio,
        sourceShortSide = listOfNotNull(p.width, p.height).minOrNull(),
        sourceFrameRate = p.frameRate,
        sourceAudioChannels = p.audioChannels,
        durationMs = p.durationMs,
    )

    private suspend fun runTranscode(session: Session, index: Int, videoOnly: Boolean) {
        val item = session.items.getOrNull(index) ?: return
        val uri = item.uri ?: return
        val probe = session.probes[index] ?: withContext(Dispatchers.IO) { probeVideo(item) }?.also { session.probes[index] = it }
        if (probe == null || !probe.videoDecodable) {
            setTranscodeUi(TranscodeUi(index, TranscodeUi.Phase.PHONE_UNSUPPORTED))
            return
        }
        if (!probe.audioDecodable && !videoOnly && probe.format.audioMime != null) {
            // 音轨解不了：只能“只转换画面”，回到确认卡让用户选择
            offerTranscode(session, index, probe, _session.value?.transcode?.offeredAt ?: TranscodeUi.OfferedAt.BEFORE_PUSH)
            return
        }
        val spec = specFor(probe, removeAudio = videoOnly)
        val transcoder = VideoTranscoder(context)
        val space = transcoder.checkSpace(session.dir, spec, item.sizeBytes)
        if (!space.enough) {
            session.transcodeReport = (session.transcodeReport ?: TranscodeReport()).copy(result = "NO_SPACE")
            setTranscodeUi(TranscodeUi(index, TranscodeUi.Phase.NO_SPACE, requiredBytes = space.requiredBytes, availableBytes = space.availableBytes))
            return
        }
        cancelTranscode(session)
        val out = File(session.dir, "v$index.mp4")
        val estimate = probe.durationMs?.let { (it * speed.factor()).toLong() }
        setTranscodeUi(
            TranscodeUi(
                index, TranscodeUi.Phase.RUNNING, estimatedDurationMs = estimate, etaMs = estimate, inPlaylist = session.items.size > 1,
                hdr = probe.format.hdr, durationMs = probe.durationMs, processedMs = 0,
            ),
        )
        _transcoding.value = true
        session.transcodeJob = scope.launch {
            val started = System.currentTimeMillis()
            val pauseWatcher = launch {
                _thermalPaused.collect { paused ->
                    _session.value?.transcode?.takeIf { it.index == index }?.let { ui ->
                        if (paused && ui.phase == TranscodeUi.Phase.RUNNING) setTranscodeUi(ui.copy(phase = TranscodeUi.Phase.PAUSED_THERMAL))
                        if (!paused && ui.phase == TranscodeUi.Phase.PAUSED_THERMAL) setTranscodeUi(ui.copy(phase = TranscodeUi.Phase.RUNNING))
                    }
                }
            }
            val result = try {
                transcoder.transcode(uri, out, spec, _thermalPaused) { p ->
                    scope.launch {
                        val elapsed = System.currentTimeMillis() - started
                        val eta = if (p in 1..99) elapsed * (100 - p) / p else null
                        _session.value?.transcode?.takeIf { it.index == index && it.phase == TranscodeUi.Phase.RUNNING }?.let {
                            val pp = maxOf(it.progress, p)
                            setTranscodeUi(it.copy(progress = pp, etaMs = eta ?: it.etaMs, processedMs = it.durationMs?.let { d -> d * pp / 100 }))
                        }
                    }
                }
            } finally {
                pauseWatcher.cancel()
                _transcoding.value = false
            }
            val report = session.transcodeReport ?: TranscodeReport(offered = true)
            when (result) {
                is TranscodeResult.Success -> {
                    probe.durationMs?.let { speed.record(result.elapsedMs, it) }
                    session.transcodeReport = report.copy(result = "SUCCESS", elapsedMs = result.elapsedMs, output = spec.outputLabel, thermalPauses = result.thermalPauses)
                    session.transcoded[index] = result.file
                    session.items = session.items.map { if (it.index == index) it.copy(transcoded = true) else it }
                    setTranscodeUi(null)
                    // 默认转换完成后自动推送（交互稿 14-A）
                    // 只在用户还停在这一项时自动推送；已经切到别处就不把他拉回来（第 1 轮 PM P2-12）
                    if (session.engine.state.value.index == index) session.engine.reload()
                }
                is TranscodeResult.Failed -> {
                    val noSpace = !transcoder.checkSpace(session.dir, spec, item.sizeBytes).enough
                    session.transcodeReport = report.copy(result = if (noSpace) "NO_SPACE" else "FAILED", errorCode = result.errorCode, thermalPauses = result.thermalPauses)
                    setTranscodeUi(TranscodeUi(index, if (noSpace) TranscodeUi.Phase.NO_SPACE else TranscodeUi.Phase.FAILED))
                }
            }
        }
    }

    private fun cancelTranscode(session: Session) {
        session.transcodeJob?.cancel()
        session.transcodeJob = null
        _transcoding.value = false
    }

    /** 每次最多保留当前项和下一项两个转换文件；照片缓存只保留前后各一张。 */
    private fun trimCaches(session: Session, index: Int) {
        session.transcoded.keys.filter { it != index && it != index + 1 }.forEach { k -> session.transcoded.remove(k)?.delete(); session.server.unregister(k) }
        session.photos.keys.filter { it < index - 1 || it > index + 1 }.forEach { k -> session.photos.remove(k)?.file?.delete(); session.server.unregister(k) }
    }

    private fun startGena(session: Session) {
        val evt = session.target.description.avTransport.eventSubUrl ?: return
        session.genaJob = scope.launch {
            val cb = session.server.genaCallbackUrl(session.localIp)
            var sub = http.subscribe(evt, cb, GENA_TIMEOUT_S) ?: return@launch
            session.genaSid = sub.sid
            while (isActive) {
                delay((sub.timeoutSeconds.coerceIn(60, 1800) * 1000L) / 2)
                sub = http.renew(evt, sub.sid, GENA_TIMEOUT_S) ?: http.subscribe(evt, cb, GENA_TIMEOUT_S) ?: break
                session.genaSid = sub.sid
            }
        }
    }

    private suspend fun endSession(session: Session, reason: PushEndReason) {
        if (session.finalized) return
        session.finalized = true
        val engineState = session.engine.state.value
        val endedWhileFailed = engineState.phase == PushPhase.FAILED
        // 先停文件服务（令牌立即作废），再尽力给电视发 Stop，保证 1 秒内端口关闭（第 1 轮 PM P2-1）
        session.server.stop()
        cancelTranscode(session)
        session.networkLossJob?.cancel()
        if (engineState.phase != PushPhase.ENDED) session.engine.end(reason)
        session.genaJob?.cancel()
        val sid = session.genaSid
        val evt = session.target.description.avTransport.eventSubUrl
        if (sid != null && evt != null) scope.launch { http.unsubscribe(evt, sid) }
        session.jobs.forEach { it.cancel() }
        renderers.saveQuirks(session.target.udn, session.engine.quirks.value.toMap())
        writeReport(session, reason)
        withContext(Dispatchers.IO) { session.dir.deleteRecursively() }
        val st = session.engine.state.value
        val items = session.items
        val info = PushEndInfo(
            reason = reason, lastIndex = st.index, lastPositionMs = st.positionMs,
            canResume = (reason == PushEndReason.DEVICE_LOST || reason == PushEndReason.NETWORK_CHANGED) &&
                (st.kind == MediaKind.VIDEO || st.kind == MediaKind.AUDIO) && st.canSeek && (st.positionMs ?: 0) > 5_000,
            durationMs = System.currentTimeMillis() - session.startedAt,
            videoCount = items.count { !it.remote && it.kind == MediaKind.VIDEO },
            photoCount = items.count { !it.remote && it.kind == MediaKind.IMAGE },
            audioCount = items.count { !it.remote && it.kind == MediaKind.AUDIO },
            urlCount = items.count { it.remote },
            failure = if (endedWhileFailed) engineState.failure?.error else null,
        )
        session.skipJob?.cancel()
        session.slowJob?.cancel()
        session.engine.close()
        _session.update { it?.copy(engine = st, ended = info, transcode = null) }
        WscLog.i(TAG, "push session ended: $reason")
    }

    private fun publish(session: Session) {
        if (s !== session) return
        val engine = session.engine.state.value
        if (engine.index != session.lastIndex) {
            // 切到新的一项：重新等待电视取走（照片“正在发送… → 已发送”，10 秒没取走提示显示慢）
            session.lastIndex = engine.index
            session.currentServed = false
            session.photoSlow = false
            session.slowJob?.cancel()
            if (session.items.getOrNull(engine.index)?.let { it.kind == MediaKind.IMAGE && !it.remote } == true) {
                session.slowJob = scope.launch {
                    delay(PHOTO_SLOW_MS)
                    if (!session.currentServed) {
                        session.photoSlow = true
                        publish(session)
                    }
                }
            }
        }
        val items = session.items.map { if (it.index == engine.index) it.copy(served = session.currentServed) else it.copy(served = false) }
        _session.update { old ->
            PushSessionState(
                sessionId = session.id, target = session.target, items = items, engine = engine,
                transcode = old?.takeIf { it.sessionId == session.id }?.transcode, startedAt = session.startedAt,
                ended = old?.takeIf { it.sessionId == session.id }?.ended,
                skipped = session.skipped, photoSlow = session.photoSlow, ownApp = session.target.description.isOwnApp,
            )
        }
    }

    private fun setTranscodeUi(ui: TranscodeUi?) {
        _session.update { it?.copy(transcode = ui) }
        // 转换中不计空闲（第 1 轮 PM P0-2）；等用户在确认卡上选择时仍按 10 分钟无操作回收
        s?.engine?.setBusy(ui != null && (ui.phase == TranscodeUi.Phase.RUNNING || ui.phase == TranscodeUi.Phase.PAUSED_THERMAL))
    }

    private fun writeReport(session: Session, reason: PushEndReason) {
        val stats = session.engine.stats.value
        val items = session.items
        val kinds = items.map { if (it.remote) "URL" else it.kind.name }.distinct()
        val maxSize = items.mapNotNull { it.sizeBytes }.maxOrNull()
        val d = session.target.description
        runCatching {
            reports.add(
                SessionRecord(
                    sessionId = session.id, protocol = CastProtocol.DLNA_PUSH, role = "SENDER", peerName = session.target.name,
                    startedAt = session.startedAt, endedAt = System.currentTimeMillis(),
                    discoveryPath = session.target.discoveryPath, discoveryMs = session.target.discoveryMs,
                    // 有转换 / 提议的会话首次播放耗时含用户决策与转换时间，不计入 P50（第 1 轮 PM P2-3）
                    firstFrameMs = stats.firstPlayMs.takeIf { session.transcodeReport?.offered != true }, success = stats.loadedCount > 0,
                    endReason = reason.name,
                    androidVersion = Build.VERSION.RELEASE.orEmpty(), deviceModel = Build.MODEL.orEmpty(),
                    push = PushReport(
                        contentType = if (kinds.size == 1) kinds.first() else "MIXED",
                        source = session.source.name,
                        mimeTypes = items.mapNotNull { it.mime }.distinct().take(5),
                        sizeBucket = maxSize?.let(::sizeBucket),
                        itemCount = maxOf(items.size, session.totalItems), switchCount = stats.switchCount, autoAdvanceCount = stats.autoAdvanceCount,
                        tvManufacturer = d.manufacturer, tvModel = listOfNotNull(d.modelName, d.modelNumber).joinToString(" ").ifBlank { null },
                        tvServer = session.target.server,
                        upnpErrorCode = stats.lastUpnpCode, httpStatus = stats.lastHttpStatus, fallbacks = stats.fallbacks.sorted(),
                        syncMode = if (stats.eventsReceived) SyncMode.EVENTS.name else SyncMode.POLL.name,
                        rangeRequests = session.server.rangeRequests,
                        transcode = session.transcodeReport,
                    ),
                ),
            )
        }.onFailure { WscLog.w(TAG, "report failed", it) }
    }

    /** 前台服务：电视最近 30 秒内在拉流，或者正在播放 → 持有唤醒锁。 */
    fun mediaStreamingRecently(): Boolean {
        val session = s ?: return false
        val last = session.server.lastRequestAt
        return session.server.activeConnections > 0 || (last > 0 && System.nanoTime() / 1_000_000 - last < 30_000)
    }

    private fun battery(): Pair<Int?, Boolean> = runCatching {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrDefault(null to false)

    /** 过热（系统温度状态达到严重级别）时暂停转换（PRD F2）。 */
    private fun registerThermalListener() {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            _thermalPaused.value = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            pm.addThermalStatusListener(context.mainExecutor) { status -> _thermalPaused.value = status >= PowerManager.THERMAL_STATUS_SEVERE }
        }
    }

    companion object {
        private const val TAG = "DmcController"
        private const val GENA_TIMEOUT_S = 300
        private const val LOW_BATTERY = 20
        private const val SKIP_DELAY_MS = 3_000L
        private const val NETWORK_DEBOUNCE_MS = 4_000L

        /**
         * 界面标题（只在手机界面显示，不进日志）。系统照片选择器出于隐私只给 “1000000020.mp4” 这种数字名，
         * 这时改用“视频 3”“照片 5”（第 1 轮 EMU-3）。
         */
        fun displayTitle(name: String?, mime: String, index: Int): String {
            val kind = when (MediaMime.kindOf(mime)) {
                MediaKind.IMAGE -> "照片"
                MediaKind.AUDIO -> "音乐"
                else -> "视频"
            }
            val n = name?.trim().orEmpty()
            if (n.isEmpty() || PICKER_ID_NAME.matches(n)) return "$kind ${index + 1}"
            return n
        }

        private val PICKER_ID_NAME = Regex("^\\d{6,}(\\.[A-Za-z0-9]{1,5})?$")

        fun userAgent(context: Context): String {
            val ver = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1"
            return "Android/${Build.VERSION.RELEASE} UPnP/1.0 WirelessScreenCasting/$ver DLNADOC/1.50"
        }
        private const val PHOTO_SLOW_MS = 10_000L

        fun sizeBucket(bytes: Long): String = when {
            bytes < 100L * 1024 * 1024 -> "<100MB"
            bytes < 1024L * 1024 * 1024 -> "<1GB"
            bytes < 4L * 1024 * 1024 * 1024 -> "<4GB"
            else -> ">=4GB"
        }

        /** 网址最后一段（只用于手机界面显示）。 */
        fun urlTitle(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val last = path.substringAfterLast('/')
            return runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last).ifBlank { url.take(60) }
        }
    }
}

/** 推送用的缓存目录（转换文件、处理后的照片）。只在 App 缓存目录，不进相册、不进备份。 */
object DmcCache {
    fun dir(context: Context): File = File(context.cacheDir, "dmc")

    fun cleanup(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }
}
