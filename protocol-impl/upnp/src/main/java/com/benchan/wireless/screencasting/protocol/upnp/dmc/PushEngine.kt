package com.benchan.wireless.screencasting.protocol.upnp.dmc

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.protocol.upnp.DidlItem
import com.benchan.wireless.screencasting.protocol.upnp.DidlLite
import com.benchan.wireless.screencasting.protocol.upnp.LastChangeEvent
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MetadataLevel
import com.benchan.wireless.screencasting.protocol.upnp.SoapOutcome
import com.benchan.wireless.screencasting.protocol.upnp.UpnpError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DLNA 推送引擎（技术方案 3.13.3 / 3.13.6，纯 JVM）：一台电视、一个播放列表。
 * 负责：加载（先 Stop → SetAVTransportURI 元数据降级 → Play 重试 → 等待 PLAYING）、轮询 / 事件同步、播完连播、
 * 结束条件、乐观播放暂停、Seek 降级、音量去抖、照片防抖与空闲超时。
 *
 * 线程：所有状态只在 [dispatcher]（必须是串行的）上修改；SOAP 由 [RendererControl] 串行。
 * 时间：[now] 单调毫秒（单测用虚拟时间）。
 */
class PushEngine(
    port: RendererPort,
    initialQuirks: RendererQuirks,
    private val hasRenderingControl: Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val timings: PushTimings = PushTimings(),
    /** 目标是本应用接收端：第一次推送时对方会弹“允许”，SetAVTransportURI 会挂起到用户确认（第 1 轮 EMU-1）。 */
    private val consentAware: Boolean = false,
) {
    private val control = RendererControl(port, timings)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(PushState(hasVolume = hasRenderingControl))
    val state: StateFlow<PushState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PushEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PushEvent> = _events.asSharedFlow()

    private val _quirks = MutableStateFlow(initialQuirks)
    /** 运行中学到的降级方式，调用方在会话结束时按 UDN 保存。 */
    val quirks: StateFlow<RendererQuirks> = _quirks.asStateFlow()

    private val _stats = MutableStateFlow(PushStats())
    val stats: StateFlow<PushStats> = _stats.asStateFlow()

    private var playlist: List<PlaylistEntry> = emptyList()
    private var preparer: MediaPreparer = MediaPreparer { PrepareResult.Failed(PushError.FILE_UNREADABLE) }
    private var current: PreparedMedia? = null
    private var monitor: PlaybackMonitor? = null

    private var loadJob: Job? = null
    private var monitorJob: Job? = null
    private var debounceJob: Job? = null
    private var volumeJob: Job? = null
    private var idleJob: Job? = null

    private var startedAt = 0L
    private var lastSuccessAt = 0L
    private var lastInteractionAt = 0L
    private var lastKnownTransport: TransportStateValue? = null
    private var foreground = true
    private var pendingPlaying: Boolean? = null
    private var pendingDeadline = 0L
    private var seekIgnoreUntil = 0L
    private var pollCount = 0
    private var capabilitiesProbed = false
    private var ended = false
    private var busy = false
    private var stuckSince = 0L
    private var budget: LoadBudget? = null

    /** 一次加载的 20 秒预算：等待对方“允许”期间不计时；同时记录最后一次已知错误（超时时按它给文案）。 */
    private class LoadBudget(val start: Long) {
        var consentMs = 0L
        var consentSince: Long? = null
        var lastFault: SoapOutcome.Fault? = null
        var lastFailure: SoapOutcome.Failure? = null
        var setAttempted = false

        fun elapsed(now: Long): Long = now - start - consentMs - (consentSince?.let { now - it } ?: 0L)
    }

    // ---------------------------------------------------------------- 公开操作

    /** 开始推送（[startIndex] 立即加载，不防抖）。 */
    fun start(entries: List<PlaylistEntry>, preparer: MediaPreparer, startIndex: Int = 0) = scope.launch {
        if (ended || entries.isEmpty()) return@launch
        startedAt = now()
        lastSuccessAt = startedAt
        lastInteractionAt = startedAt
        setPlaylist(entries, preparer)
        startIdleWatchdog()
        load(startIndex.coerceIn(0, entries.lastIndex))
    }

    /** 换内容：同一台电视上替换整个列表（PRD 8.1.1 D“换内容”）。 */
    fun replace(entries: List<PlaylistEntry>, preparer: MediaPreparer, startIndex: Int = 0) = scope.launch {
        if (ended || entries.isEmpty()) return@launch
        touch()
        setPlaylist(entries, preparer)
        bumpSwitch()
        load(startIndex.coerceIn(0, entries.lastIndex))
    }

    /** 切到列表中的某项：先更新界面，400ms 防抖后才真正推送（快速连续切换时只推最后一项）。 */
    fun select(index: Int) = scope.launch {
        if (ended || index !in playlist.indices) return@launch
        touch()
        bumpSwitch()
        stopMonitor()
        loadJob?.cancel()
        _state.update { it.copy(index = index, kind = playlist[index].kind, phase = PushPhase.LOADING, failure = null, positionMs = null, durationMs = null) }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(timings.debounceMs)
            load(index)
        }
    }

    fun next() = scope.launch { _state.value.index.let { if (it + 1 < playlist.size) select(it + 1) } }
    fun previous() = scope.launch { _state.value.index.let { if (it > 0) select(it - 1) } }

    /** 重新加载当前项（例如转换完成后，调用方的 preparer 会返回转换后的文件）。 */
    fun reload() = scope.launch {
        if (ended) return@launch
        touch()
        load(_state.value.index)
    }

    fun play() = scope.launch { setPlaying(true) }
    fun pause() = scope.launch { setPlaying(false) }
    fun togglePlay() = scope.launch { setPlaying(_state.value.phase != PushPhase.PLAYING) }

    fun seekTo(positionMs: Long) = scope.launch {
        if (ended) return@launch
        touch()
        val s = _state.value
        if (!s.canSeek || current == null || s.kind == MediaKind.IMAGE) {
            _events.tryEmit(PushEvent.SeekUnsupported)
            return@launch
        }
        val target = positionMs.coerceAtLeast(0).let { p -> s.durationMs?.let { p.coerceAtMost(it) } ?: p }
        val q = _quirks.value
        var out = control.seek(q.seekUnit, target)
        note(out)
        if (out is SoapOutcome.Fault && out.code == UpnpError.UNSUPPORTED_SEEK_MODE.code) {
            val other = if (q.seekUnit == SeekUnit.REL_TIME) SeekUnit.ABS_TIME else SeekUnit.REL_TIME
            out = control.seek(other, target)
            note(out)
            if (out is SoapOutcome.Ok) {
                _quirks.update { it.copy(seekUnit = other) }
                fallback("seek_${other.name.lowercase()}")
            }
        }
        when (out) {
            is SoapOutcome.Ok -> {
                seekIgnoreUntil = now() + timings.seekIgnoreMs
                _state.update { it.copy(positionMs = target) }
            }
            is SoapOutcome.Fault -> {
                _quirks.update { it.copy(seekUnsupported = true) }
                _state.update { it.copy(canSeek = false) }
                _events.tryEmit(PushEvent.SeekUnsupported)
            }
            is SoapOutcome.Failure -> Unit
        }
    }

    /** 音量（0~100）：界面乐观更新，300ms 去抖后发给电视。 */
    fun setVolume(volume: Int) = scope.launch {
        if (ended || !_state.value.hasVolume) return@launch
        touch()
        val v = volume.coerceIn(0, 100)
        _state.update { it.copy(volume = v) }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(timings.volumeDebounceMs)
            val out = control.setVolume(v)
            note(out)
            if (out is SoapOutcome.Fault && out.code == UpnpError.INVALID_ACTION.code) _state.update { it.copy(hasVolume = false) }
        }
    }

    /** 音量键（系统远程音量）：在当前值上加减。 */
    fun adjustVolume(delta: Int) = scope.launch {
        val cur = _state.value.volume ?: return@launch
        setVolume(cur + delta)
    }

    fun setMute(muted: Boolean) = scope.launch {
        if (ended || !_state.value.hasVolume) return@launch
        touch()
        _state.update { it.copy(muted = muted) }
        note(control.setMute(muted))
    }

    /** 遥控页是否在前台：决定轮询频率（前台 1s，后台 5s）。 */
    fun setForeground(visible: Boolean) = scope.launch { foreground = visible }

    /** 用户有操作（或点了空闲提醒里的“继续”）。 */
    fun keepAlive() = scope.launch { touch() }

    /** 调用方正在做耗时的前置步骤（转换视频 / 等用户在确认卡上选择）：期间不计空闲（第 1 轮 PM P0-2）。 */
    fun setBusy(value: Boolean) = scope.launch {
        busy = value
        stuckSince = now()
    }

    /** 文件服务发现当前项读不到（授权中途失效等）：转为失败卡（第 1 轮 PM P2-17）。 */
    fun reportError(index: Int, error: PushError) = scope.launch {
        if (ended || _state.value.index != index) return@launch
        val p = _state.value.phase
        if (p == PushPhase.FAILED || p == PushPhase.AWAITING_CHOICE) return@launch
        loadJob?.cancel()
        stopMonitor()
        fail(index, PushFailure(error))
    }

    /** GENA 事件（只用来加快更新；轮询仍然进行）。 */
    fun onEvent(event: LastChangeEvent) = scope.launch {
        if (ended) return@launch
        _stats.update { it.copy(eventsReceived = true) }
        _state.update { s ->
            s.copy(
                syncMode = SyncMode.EVENTS,
                volume = event.volume ?: s.volume,
                muted = event.mute ?: s.muted,
            )
        }
        val ts = event.transportState?.let(TransportStateValue::of)
        if (ts != null && monitorJob?.isActive == true) applySample(ts, event.trackUri, null, null)
    }

    /** 结束推送。按原因决定是否给电视发 Stop（被接管时绝不发，免得停掉别人的内容）。挂起直到完成。 */
    suspend fun end(reason: PushEndReason) = withContext(dispatcher) { endInternal(reason) }

    /** 释放（不发任何请求）。 */
    fun close() {
        scope.cancel()
    }

    // ---------------------------------------------------------------- 加载

    private fun setPlaylist(entries: List<PlaylistEntry>, p: MediaPreparer) {
        playlist = entries
        preparer = p
        _state.update { it.copy(count = entries.size) }
    }

    private fun load(index: Int) {
        debounceJob?.cancel()
        loadJob?.cancel()
        stopMonitor()
        loadJob = scope.launch { doLoad(index) }
    }

    private suspend fun doLoad(index: Int) {
        val entry = playlist[index]
        _state.update {
            it.copy(
                phase = PushPhase.PREPARING, index = index, kind = entry.kind, failure = null, loadingSlow = false,
                positionMs = null, durationMs = null, idleWarning = false, awaitingConsent = false,
                canSeek = !_quirks.value.seekUnsupported, canPause = true,
            )
        }
        val media = when (val r = preparer.prepare(index)) {
            is PrepareResult.Ready -> r.media
            is PrepareResult.Failed -> return fail(index, PushFailure(r.error))
            is PrepareResult.Deferred -> return awaitChoice(index, PushFailure(r.error))
        }
        current = media
        _state.update { it.copy(phase = PushPhase.LOADING, kind = media.kind) }
        val b = LoadBudget(now())
        budget = b
        val slowHint = scope.launch {
            // 10 秒（不含等对方“允许”的时间）起提示电视上可能要点“允许”
            while (isActive && b.elapsed(now()) < timings.slowHintMs) delay(250)
            _state.update { if (it.phase == PushPhase.LOADING) it.copy(loadingSlow = true) else it }
        }
        val failure = try {
            coroutineScope {
                val work = async { setAndPlay(media, b) }
                // 总预算 20 秒（第 1 轮 PM P1-1）：前置步骤各自的超时加起来可能超过 20 秒，由看门狗统一收口
                val dog = launch {
                    while (isActive) {
                        delay(250)
                        if (b.elapsed(now()) >= timings.startTimeoutMs) {
                            work.cancel()
                            return@launch
                        }
                    }
                }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    timeoutFailure(media, b)
                } finally {
                    dog.cancel()
                }
            }
        } finally {
            slowHint.cancel()
            budget = null
            _state.update { if (it.awaitingConsent) it.copy(awaitingConsent = false) else it }
        }
        if (failure != null) {
            // 放弃时尽力让电视停下，避免“电视在播、手机以为失败”（第 1 轮 EMU-1：对方稍后才点允许）
            if (b.setAttempted) scope.launch { withTimeoutOrNull(3_000) { control.stop(2_000) } }
            return fail(index, failure)
        }
        onLoaded(index, media)
    }

    /** 20 秒没结果：按最后一次已知错误给出失败原因。 */
    private fun timeoutFailure(media: PreparedMedia, b: LoadBudget): PushFailure {
        val f = b.lastFault
        return when {
            f?.code == UpnpError.ILLEGAL_MIME.code || f?.code == UpnpError.FORMAT_NOT_SUPPORTED.code -> PushFailure(formatError(media), f.code, f.httpStatus)
            f?.code == UpnpError.ACCESS_DENIED.code -> PushFailure(PushError.REJECTED, f.code, f.httpStatus)
            f?.code == UpnpError.RESOURCE_NOT_FOUND.code -> PushFailure(if (media.remote) PushError.URL_FAILED else PushError.UNSUPPORTED_FORMAT, f.code, f.httpStatus)
            b.lastFailure != null && f == null -> PushFailure(PushError.UNREACHABLE, httpStatus = b.lastFailure?.httpStatus)
            media.remote -> PushFailure(PushError.URL_FAILED, f?.code, f?.httpStatus)
            else -> PushFailure(PushError.START_TIMEOUT, f?.code, f?.httpStatus)
        }
    }

    /** 推送前需要用户选择（例如提议转换）：中性状态，不是失败。 */
    private fun awaitChoice(index: Int, failure: PushFailure) {
        current = null
        stuckSince = now()
        _state.update { it.copy(phase = PushPhase.AWAITING_CHOICE, failure = failure, loadingSlow = false) }
        _events.tryEmit(PushEvent.Failed(index, failure))
    }

    /** SetAVTransportURI + Play + 等待开始播放。成功返回 null。 */
    private suspend fun setAndPlay(media: PreparedMedia, b: LoadBudget): PushFailure? {
        val q = _quirks.value
        // 1. 先 Stop（视频 / 音频默认；照片不先 Stop，避免翻页闪黑）
        if (media.kind != MediaKind.IMAGE && q.stopBeforeSet) {
            val known = lastKnownTransport ?: (control.transportInfo().also(::note) as? SoapOutcome.Ok)?.let { TransportInfo.from(it.values).state }
            if (known == TransportStateValue.PLAYING || known == TransportStateValue.PAUSED || known == TransportStateValue.TRANSITIONING) {
                note(control.stop())
                delay(timings.stopSettleMs)
            }
        }
        // 2. SetAVTransportURI：元数据 完整 → 精简 → 不带
        val didl = DidlItem(media.title, media.kind, media.protocolInfo, media.url, media.sizeBytes, media.durationMs, media.resolution)
        val levels = MetadataLevel.entries.drop(q.metadataLevel.ordinal)
        var stoppedForRetry = false
        var lastFault: SoapOutcome.Fault? = null
        var accepted = false
        levelLoop@ for (level in levels) {
            while (true) {
                b.setAttempted = true
                if (consentAware) {
                    b.consentSince = now()
                    _state.update { it.copy(awaitingConsent = true) }
                }
                val out = try {
                    control.setUri(media.url, DidlLite.build(didl, level), if (consentAware) timings.consentTimeoutMs else timings.loadTimeoutMs)
                } finally {
                    b.consentSince?.let { since -> b.consentMs += now() - since }
                    b.consentSince = null
                    if (consentAware) _state.update { it.copy(awaitingConsent = false) }
                }
                note(out)
                when (out) {
                    is SoapOutcome.Ok -> {
                        if (level != q.metadataLevel) {
                            _quirks.update { it.copy(metadataLevel = level) }
                            fallback("metadata_${level.name.lowercase()}")
                        }
                        accepted = true
                        break@levelLoop
                    }
                    is SoapOutcome.Failure -> return PushFailure(PushError.UNREACHABLE, httpStatus = out.httpStatus)
                    is SoapOutcome.Fault -> {
                        lastFault = out
                        when (out.code) {
                            UpnpError.TRANSITION_NOT_AVAILABLE.code, UpnpError.TRANSPORT_LOCKED.code, UpnpError.CONTENT_BUSY.code -> {
                                if (stoppedForRetry) continue@levelLoop
                                stoppedForRetry = true
                                note(control.stop())
                                delay(timings.stopSettleMs)
                                fallback("stop_before_set")
                                continue // 同一档位重试
                            }
                            UpnpError.ILLEGAL_MIME.code, UpnpError.FORMAT_NOT_SUPPORTED.code ->
                                return PushFailure(formatError(media), out.code, out.httpStatus)
                            UpnpError.RESOURCE_NOT_FOUND.code, UpnpError.READ_ERROR.code, UpnpError.NO_CONTENTS.code ->
                                return PushFailure(if (media.remote) PushError.URL_FAILED else PushError.UNSUPPORTED_FORMAT, out.code, out.httpStatus)
                            UpnpError.ACCESS_DENIED.code -> return PushFailure(PushError.REJECTED, out.code, out.httpStatus)
                            else -> continue@levelLoop // 402 / 501 / 未知：降一档元数据
                        }
                    }
                }
            }
        }
        if (!accepted) {
            val f = lastFault
            return PushFailure(if (f?.httpStatus == 401 || f?.httpStatus == 403) PushError.REJECTED else formatError(media), f?.code, f?.httpStatus)
        }
        // 3. Play（加载中报 701 时等一会再试，5 秒内最多 3 次）
        var played = false
        for (attempt in 0..timings.playRetryDelaysMs.size) {
            val out = control.play()
            note(out)
            if (out is SoapOutcome.Ok) {
                played = true
                break
            }
            if (out is SoapOutcome.Fault && out.code == UpnpError.TRANSITION_NOT_AVAILABLE.code && attempt < timings.playRetryDelaysMs.size) {
                delay(timings.playRetryDelaysMs[attempt])
                continue
            }
            if (out is SoapOutcome.Fault && out.code == UpnpError.ACCESS_DENIED.code) return PushFailure(PushError.REJECTED, out.code, out.httpStatus)
            break // 有的电视已自动播放、对 Play 报错：继续等状态
        }
        if (!played) fallback("play_error_ignored")
        // 4. 照片：电视的图片状态不可靠，命令成功即视为已显示
        if (media.kind == MediaKind.IMAGE) return null
        // 5. 等待 PLAYING（总计 20 秒）
        var sawActive = false
        var stoppedAfterActive = 0
        while (true) {
            val out = control.transportInfo()
            note(out)
            if (out is SoapOutcome.Ok) {
                val ti = TransportInfo.from(out.values)
                lastKnownTransport = ti.state
                if (ti.error) return PushFailure(formatError(media))
                when (ti.state) {
                    TransportStateValue.PLAYING -> return null
                    TransportStateValue.PAUSED -> if (sawActive) return null
                    TransportStateValue.TRANSITIONING -> sawActive = true
                    TransportStateValue.STOPPED, TransportStateValue.NO_MEDIA -> if (sawActive && ++stoppedAfterActive >= 2) return PushFailure(formatError(media))
                    TransportStateValue.UNKNOWN -> Unit
                }
            } else if (out is SoapOutcome.Failure && now() - lastSuccessAt >= timings.lostMs) {
                return PushFailure(PushError.UNREACHABLE)
            }
            delay(timings.startPollMs)
        }
    }

    private fun formatError(media: PreparedMedia) = if (media.remote) PushError.URL_FAILED else PushError.UNSUPPORTED_FORMAT

    private suspend fun onLoaded(index: Int, media: PreparedMedia) {
        val t = now()
        _stats.update { s ->
            s.copy(firstPlayMs = s.firstPlayMs ?: (t - startedAt), loadedCount = s.loadedCount + 1)
        }
        _state.update {
            it.copy(
                phase = if (media.kind == MediaKind.IMAGE) PushPhase.SHOWING else PushPhase.PLAYING,
                loadingSlow = false, durationMs = media.durationMs, positionMs = if (media.kind == MediaKind.IMAGE) null else 0,
            )
        }
        lastKnownTransport = if (media.kind == MediaKind.IMAGE) null else TransportStateValue.PLAYING
        _events.tryEmit(PushEvent.Loaded(index))
        if (!capabilitiesProbed) {
            capabilitiesProbed = true
            probeCapabilities()
        }
        monitor = PlaybackMonitor(media.kind, media.ownershipKey, media.remote, timings)
        startMonitor()
    }

    private suspend fun probeCapabilities() {
        (control.transportActions().also(::note) as? SoapOutcome.Ok)?.values?.get("Actions")?.let { actions ->
            if (actions.isNotBlank()) {
                val list = actions.split(',').map { it.trim().uppercase() }
                // 只在电视明确列出动作时才据此隐藏（很多电视不实现这个动作）
                if (list.any { it == "PLAY" || it == "STOP" }) {
                    _state.update { it.copy(canSeek = it.canSeek && list.any { a -> a.startsWith("SEEK") }, canPause = list.contains("PAUSE")) }
                }
            }
        }
        if (hasRenderingControl) {
            when (val v = control.getVolume().also(::note)) {
                is SoapOutcome.Ok -> _state.update { it.copy(volume = v.values["CurrentVolume"]?.trim()?.toIntOrNull(), hasVolume = v.values["CurrentVolume"]?.trim()?.toIntOrNull() != null) }
                else -> _state.update { it.copy(hasVolume = false) }
            }
            (control.getMute().also(::note) as? SoapOutcome.Ok)?.values?.get("CurrentMute")?.trim()?.let { m ->
                _state.update { it.copy(muted = m == "1" || m.equals("true", true)) }
            }
        }
    }

    private fun fail(index: Int, failure: PushFailure) {
        current = null
        stuckSince = now()
        _stats.update { it.copy(lastUpnpCode = failure.upnpCode ?: it.lastUpnpCode, lastHttpStatus = failure.httpStatus ?: it.lastHttpStatus) }
        _state.update { it.copy(phase = PushPhase.FAILED, failure = failure, loadingSlow = false) }
        _events.tryEmit(PushEvent.Failed(index, failure))
        WscLog.w(TAG, "item $index failed: ${failure.error} upnp=${failure.upnpCode} http=${failure.httpStatus}")
    }

    // ---------------------------------------------------------------- 状态同步

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                // 观察到停止时加快复查（后台也要 10 秒内发现“电视已停止”，第 1 轮 PM P2-18）
                val interval = if (foreground || monitor?.stopPending == true) timings.pollForegroundMs else timings.pollBackgroundMs
                delay(interval)
                poll(interval)
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun poll(interval: Long) {
        pollCount++
        val ti = control.transportInfo().also(::note)
        if (ti is SoapOutcome.Failure) {
            if (now() - lastSuccessAt >= timings.lostMs) endInternal(PushEndReason.DEVICE_LOST)
            return
        }
        val pi = control.positionInfo().also(::note)
        val transport = (ti as? SoapOutcome.Ok)?.values?.let(TransportInfo::from)
        val position = (pi as? SoapOutcome.Ok)?.values?.let(PositionInfo::from)
        // 网址推送：用 GetMediaInfo.CurrentURI（我们设置的原始地址）判断是否被接管，CDN 跳转后的 TrackURI 不可靠
        val ownerUri = if (current?.remote == true) {
            (control.mediaInfo().also(::note) as? SoapOutcome.Ok)?.values?.get("CurrentURI")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NOT_IMPLEMENTED", true) }
        } else position?.trackUri
        if (pi is SoapOutcome.Fault && _state.value.positionMs != null && pollCount > 3) {
            _state.update { it.copy(positionMs = null) } // 电视不报告进度 → 界面隐藏进度条
        }
        if (foreground && _state.value.hasVolume && pollCount % 5 == 0 && _state.value.syncMode == SyncMode.POLL) {
            (control.getVolume().also(::note) as? SoapOutcome.Ok)?.values?.get("CurrentVolume")?.trim()?.toIntOrNull()?.let { v ->
                if (volumeJob?.isActive != true) _state.update { it.copy(volume = v) }
            }
        }
        applySample(transport?.state, ownerUri, position?.positionMs, position?.durationMs, interval)
    }

    private suspend fun applySample(state: TransportStateValue?, trackUri: String?, positionMs: Long?, durationMs: Long?, interval: Long = timings.pollForegroundMs) {
        if (ended) return
        val m = monitor ?: return
        if (state != null && state != TransportStateValue.UNKNOWN) lastKnownTransport = state
        val decision = m.onSample(now(), state, trackUri, positionMs, durationMs, interval)
        when (decision) {
            MonitorDecision.TAKEN_OVER -> return endInternal(PushEndReason.TAKEN_OVER)
            MonitorDecision.FINISHED -> return onFinished()
            MonitorDecision.STOPPED -> return endInternal(PushEndReason.STOPPED_ON_TV)
            MonitorDecision.CONTINUE -> Unit
        }
        val t = now()
        _state.update { s ->
            var phase = s.phase
            if (s.kind != MediaKind.IMAGE && state != null) {
                val tvPlaying = when (state) {
                    TransportStateValue.PLAYING -> true
                    TransportStateValue.PAUSED -> false
                    else -> null
                }
                val pending = pendingPlaying
                if (pending != null) {
                    if (tvPlaying == pending) {
                        pendingPlaying = null
                    } else if (t >= pendingDeadline) {
                        pendingPlaying = null
                        _events.tryEmit(PushEvent.NoResponse)
                        if (tvPlaying != null) phase = if (tvPlaying) PushPhase.PLAYING else PushPhase.PAUSED
                    }
                } else if (tvPlaying != null) {
                    phase = if (tvPlaying) PushPhase.PLAYING else PushPhase.PAUSED
                }
            }
            s.copy(
                phase = phase,
                positionMs = if (t < seekIgnoreUntil) s.positionMs else (positionMs ?: s.positionMs),
                durationMs = durationMs ?: s.durationMs,
            )
        }
    }

    private suspend fun onFinished() {
        val next = _state.value.index + 1
        if (next < playlist.size) {
            _stats.update { it.copy(autoAdvanceCount = it.autoAdvanceCount + 1) }
            _events.tryEmit(PushEvent.AutoAdvanced(next))
            lastKnownTransport = TransportStateValue.STOPPED
            load(next)
        } else {
            endInternal(PushEndReason.LIST_FINISHED)
        }
    }

    private suspend fun setPlaying(playing: Boolean) {
        if (ended) return
        touch()
        val s = _state.value
        if (s.kind == MediaKind.IMAGE || (s.phase != PushPhase.PLAYING && s.phase != PushPhase.PAUSED)) return
        if (!playing && !s.canPause) return
        pendingPlaying = playing
        pendingDeadline = now() + timings.optimisticMs
        _state.update { it.copy(phase = if (playing) PushPhase.PLAYING else PushPhase.PAUSED) }
        val out = if (playing) control.play() else control.pause()
        note(out)
        if (out is SoapOutcome.Fault && !playing && out.code == UpnpError.INVALID_ACTION.code) {
            _state.update { it.copy(canPause = false) }
        }
    }

    // ---------------------------------------------------------------- 空闲与结束

    private fun startIdleWatchdog() {
        idleJob?.cancel()
        idleJob = scope.launch {
            var warned = false
            while (isActive) {
                delay(timings.idleCheckMs)
                val s = _state.value
                // 照片 30 分钟无操作（只对“正在显示照片”生效；转换 / 等待选择不算，第 1 轮 PM P0-2）
                if (s.kind == MediaKind.IMAGE && s.phase == PushPhase.SHOWING) {
                    val idle = now() - lastInteractionAt
                    if (idle >= timings.photoIdleMs) return@launch endInternal(PushEndReason.IDLE_TIMEOUT)
                    if (idle >= timings.photoIdleWarnMs && !warned) {
                        warned = true
                        _state.update { it.copy(idleWarning = true) }
                        _events.tryEmit(PushEvent.IdleWarning)
                    }
                    continue
                }
                warned = false
                // 失败 / 等待选择后长时间没人管：结束并释放前台服务（调用方在转换时 setBusy(true)，不计）
                if ((s.phase == PushPhase.FAILED || s.phase == PushPhase.AWAITING_CHOICE) && !busy) {
                    if (now() - maxOf(lastInteractionAt, stuckSince) >= timings.inactiveMs) return@launch endInternal(PushEndReason.INACTIVE)
                }
            }
        }
    }

    private fun touch() {
        lastInteractionAt = now()
        if (_state.value.idleWarning) _state.update { it.copy(idleWarning = false) }
    }

    private suspend fun endInternal(reason: PushEndReason) {
        if (ended) return
        ended = true
        val self = kotlin.coroutines.coroutineContext[Job]
        listOf(loadJob, monitorJob, debounceJob, volumeJob, idleJob).forEach { j -> if (j != null && j != self) j.cancel() }
        if (reason == PushEndReason.USER_STOPPED || reason == PushEndReason.IDLE_TIMEOUT || reason == PushEndReason.NETWORK_CHANGED) {
            withTimeoutOrNull(timings.stopOnEndTimeoutMs) { control.stop(timings.stopOnEndTimeoutMs) }
        }
        _state.update { it.copy(phase = PushPhase.ENDED, endReason = reason, loadingSlow = false, idleWarning = false) }
        _events.tryEmit(PushEvent.Ended(reason))
        WscLog.i(TAG, "push ended: $reason")
    }

    private fun note(out: SoapOutcome) {
        if (out !is SoapOutcome.Failure) lastSuccessAt = now()
        budget?.let { b ->
            when (out) {
                is SoapOutcome.Fault -> b.lastFault = out
                is SoapOutcome.Failure -> b.lastFailure = out
                is SoapOutcome.Ok -> Unit
            }
        }
        if (out is SoapOutcome.Fault) _stats.update { it.copy(lastUpnpCode = out.code ?: it.lastUpnpCode, lastHttpStatus = out.httpStatus) }
    }

    private fun fallback(name: String) = _stats.update { it.copy(fallbacks = it.fallbacks + name) }

    private fun bumpSwitch() = _stats.update { it.copy(switchCount = it.switchCount + 1) }

    private companion object {
        const val TAG = "PushEngine"
    }
}
