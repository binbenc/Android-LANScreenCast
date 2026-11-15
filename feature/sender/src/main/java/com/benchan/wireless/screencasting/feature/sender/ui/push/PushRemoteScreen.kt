package com.benchan.wireless.screencasting.feature.sender.ui.push

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benchan.wireless.screencasting.core.ui.components.ProtocolTag
import com.benchan.wireless.screencasting.core.ui.components.WscButtonColors
import com.benchan.wireless.screencasting.core.ui.theme.ScrimAlpha
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.core.ui.theme.onScrim
import com.benchan.wireless.screencasting.feature.sender.ui.reportFooterHeight
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushItemInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 遥控页上的动作（由 [PushFullScreen] 接到具体页面）。 */
internal class PushRemoteActions(
    val onBack: () -> Unit,
    val onChangeContent: () -> Unit,
    val onShowPlaylist: () -> Unit,
    val onFailureAction: (PushCopy.Action) -> Unit,
    /** P05 取消：发 Stop、停文件服务，回到内容面板（同一台，不再确认）。 */
    val onCancelLoading: () -> Unit,
    /** P11 取消转换（界面再给 Snackbar“已取消转换 [直接尝试原文件]”）。 */
    val onCancelTranscode: () -> Unit,
    /** 等你选择：重新打开转换提议。 */
    val onChooseTranscode: () -> Unit,
    /** 页内一次性提示（Snackbar）。 */
    val onMessage: (String) -> Unit,
)

/**
 * 控件区显示什么。数据随状态一起进入 Crossfade（退场动画只读自己的参数，不读最新状态，第 1 轮 UX P0-1）。
 */
private sealed interface Area {
    data class Transcode(val t: TranscodeUi) : Area
    data class Failure(val f: PushCopy.Failure, val error: PushError) : Area
    data object AwaitingChoice : Area
    data class Loading(val index: Int) : Area
    data object Photo : Area
    data object Player : Area
}

private fun areaOf(s: PushSessionState): Area {
    val t = s.transcode
    val e = s.engine
    return when {
        t != null && (t.phase == TranscodeUi.Phase.RUNNING || t.phase == TranscodeUi.Phase.PAUSED_THERMAL) -> Area.Transcode(t)
        t != null && t.phase == TranscodeUi.Phase.FAILED ->
            Area.Failure(PushCopy.failure(PushError.TRANSCODE_FAILED, s.target.name, s.current, s.ownApp), PushError.TRANSCODE_FAILED)
        e.phase == PushPhase.AWAITING_CHOICE -> Area.AwaitingChoice
        e.phase == PushPhase.FAILED -> (e.failure?.error ?: PushError.UNREACHABLE).let { Area.Failure(PushCopy.failure(it, s.target.name, s.current, s.ownApp), it) }
        e.phase == PushPhase.PREPARING || e.phase == PushPhase.LOADING -> Area.Loading(e.index)
        e.phase == PushPhase.SHOWING -> Area.Photo
        else -> Area.Player
    }
}

/**
 * 推送遥控页（交互稿 P05 连接 / 加载、P06 视频、P07 音乐与能力降级、P09 照片、P11 转换进度、P13 失败卡）。
 * 自上而下：推到哪 → 放的是什么 → 怎么控制 → 怎么停。返回 = 回设备列表，推送继续。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushRemoteScreen(s: PushSessionState, vm: PushViewModel, actions: PushRemoteActions) {
    val c = MaterialTheme.colorScheme
    val e = s.engine
    val item = s.current
    val area = areaOf(s)
    val big = LocalDensity.current.fontScale >= 1.5f
    // 照片隐私说明只在本次会话第一张照片显示（第 1 轮 UX P2-9）
    var firstPhotoIndex by rememberSaveable(s.sessionId) { mutableIntStateOf(-1) }
    if (firstPhotoIndex < 0 && item?.kind == MediaKind.IMAGE) firstPhotoIndex = item.index
    Column(Modifier.fillMaxSize().background(c.surface)) {
        TopAppBar(
            title = { Text("推送中") },
            navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回设备列表，推送继续") } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceHeader(s)
            if (e.idleWarning) IdleBanner(vm::keepAlive)
            if (item != null) {
                val photoView = item.kind == MediaKind.IMAGE && !item.remote && (area is Area.Photo || area is Area.Loading)
                if (photoView) {
                    PhotoArea(s, item, vm, showPrivacyNote = item.index == firstPhotoIndex)
                } else {
                    Thumbnail(item, e.count, big)
                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(PushCopy.itemSubtitle(item, e.count), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                if (e.count > 1 && (item.kind == MediaKind.IMAGE || s.items.any { it.kind == MediaKind.IMAGE })) FilmStrip(s, vm)
            }
            Crossfade(targetState = area, animationSpec = tween(200), label = "push-area") { a ->
                when (a) {
                    is Area.Transcode -> TranscodeCard(s.target.name, a.t)
                    is Area.Failure -> FailureCard(a.f, a.error, actions.onFailureAction)
                    Area.AwaitingChoice -> AwaitingChoiceCard(actions.onChooseTranscode)
                    is Area.Loading -> LoadingCard(s)
                    Area.Photo -> Unit
                    Area.Player -> PlayerProgressAndVolume(s, vm)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // 播放控件行与“列表 / 换内容”一起固定在滚动区外：200% 字号时也始终可见（第 1 轮 UX P2-13），
        // 默认字号下也不会出现被半遮的按钮（第 2 轮复测）
        if (area is Area.Player) PlayerButtons(s, vm, actions.onMessage)
        // 列表 / 换内容：只在正常播放 / 显示时出现（第 1 轮 UX P2-3）
        if (area is Area.Player || area is Area.Photo) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.count > 1) FilledTonalButton(onClick = actions.onShowPlaylist, Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("列表")
                }
                FilledTonalButton(onClick = actions.onChangeContent, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("换内容")
                }
            }
        }
        Footer(area, s, vm, actions)
    }
}

/** 页脚按状态切换（第 1 轮 UX P1-1）。 */
@Composable
private fun Footer(area: Area, s: PushSessionState, vm: PushViewModel, actions: PushRemoteActions) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().reportFooterHeight().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (area) {
            is Area.Loading -> OutlinedButton(onClick = actions.onCancelLoading, Modifier.fillMaxWidth().height(56.dp)) { Text("取消") }
            is Area.Transcode -> {
                OutlinedButton(onClick = actions.onCancelTranscode, Modifier.fillMaxWidth().height(56.dp)) { Text("取消转换") }
                if (area.t.inPlaylist) {
                    FilledTonalButton(onClick = vm::skipItem, Modifier.fillMaxWidth()) {
                        Text("跳过这一项"); Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                    }
                } else {
                    TextButton(onClick = vm::tryOriginal, Modifier.fillMaxWidth()) { Text("改为直接尝试原文件") }
                }
            }
            is Area.Failure, Area.AwaitingChoice -> OutlinedButton(onClick = vm::stop, Modifier.fillMaxWidth().height(56.dp)) { Text("结束推送") }
            else -> Button(
                onClick = vm::stop, Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer),
            ) { Text("结束推送") }
        }
    }
}

@Composable
private fun DeviceHeader(s: PushSessionState) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val e = s.engine
    val label = PushCopy.statusLabel(s)
    val audioOnly = PushCopy.isAudioOnly(s.target.sink)
    val transcoding = s.transcode?.phase == TranscodeUi.Phase.RUNNING
    val busy = transcoding || e.phase == PushPhase.LOADING || e.phase == PushPhase.PREPARING || (e.phase == PushPhase.SHOWING && s.current?.served != true)
    val failed = e.phase == PushPhase.FAILED || s.transcode?.phase == TranscodeUi.Phase.FAILED
    val sent = e.phase == PushPhase.SHOWING && s.current?.served == true
    val color = when {
        failed -> c.error
        busy -> c.primary
        e.phase == PushPhase.PLAYING || sent -> ext.success
        else -> c.onSurfaceVariant
    }
    // 同一状态 2 秒内不重复播报
    var spoken by remember { mutableStateOf("") }
    LaunchedEffect(label) { delay(2_000); spoken = label }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(c.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(if (audioOnly) Icons.Rounded.MusicNote else Icons.Rounded.PlayCircleOutline, null, tint = c.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(s.target.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProtocolTag("DLNA")
                Text(
                    when {
                        s.ownApp -> "本应用接收端"
                        audioOnly -> "只能推送音乐"
                        else -> "电视自带投屏"
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = if (spoken == label) "${s.target.name} $label" else "${s.target.name} $label"
            },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                failed -> Icon(Icons.Rounded.Close, null, Modifier.size(16.dp), tint = color)
                busy -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
                sent -> Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = color)
                else -> Box(Modifier.size(8.dp).background(color, CircleShape))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (sent) c.onSurfaceVariant else color)
        }
    }
}

/** 照片 30 分钟无操作的前台提醒（第 1 轮 PM P2-19）。 */
@Composable
private fun IdleBanner(onContinue: () -> Unit) {
    val ext = WscTheme.extended
    Row(
        Modifier.fillMaxWidth().background(ext.warningContainer, RoundedCornerShape(12.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("照片推送将在 1 分钟后结束", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, color = ext.onWarningContainer, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onContinue) { Text("继续", color = ext.onWarningContainer) }
    }
}

/** 本地缩略图（不抓取网络缩略图；网址用链接图标占位）。 */
@Composable
internal fun rememberThumbnail(uri: Uri?, w: Int, h: Int): Bitmap? {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(w, h), null) }.getOrNull()
        }
    }
    return bmp
}

@Composable
private fun Thumbnail(item: PushItemInfo, count: Int, big: Boolean) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().aspectRatio(if (big) 2.4f else 16f / 9f).background(c.scrim, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            item.remote -> Icon(Icons.Rounded.Link, null, Modifier.size(48.dp), tint = c.onScrim)
            item.kind == MediaKind.AUDIO -> Box(Modifier.fillMaxSize().background(c.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(56.dp), tint = c.onPrimaryContainer)
            }
            else -> rememberThumbnail(item.uri, 640, 360)?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(kindIcon(item.kind), null, Modifier.size(48.dp), tint = c.onScrim)
        }
        if (count > 1) CountBadge("${item.index + 1} / $count", Modifier.align(Alignment.BottomEnd))
    }
}

/** “3 / 12”角标：scrim 60% + onScrim；随字号放大最多 1.3 倍（第 1 轮 UX P2-12）。 */
@Composable
private fun CountBadge(text: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.3f))) {
        Text(
            text, modifier.padding(8.dp).background(c.scrim.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            color = c.onScrim, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
        )
    }
}

/** P09 照片：大图左右滑动（本地预览立即切换，推送防抖由引擎处理）+ 上一张 / 下一张。 */
@Composable
private fun PhotoArea(s: PushSessionState, item: PushItemInfo, vm: PushViewModel, showPrivacyNote: Boolean) {
    val c = MaterialTheme.colorScheme
    val e = s.engine
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(c.scrim, RoundedCornerShape(16.dp))
            .pointerInput(e.index) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -80) vm.next() else if (drag > 80) vm.previous()
                        drag = 0f
                    },
                ) { _, amount -> drag += amount }
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上一张") { vm.previous(); true },
                    CustomAccessibilityAction("下一张") { vm.next(); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        rememberThumbnail(item.uri, 1280, 960)?.let {
            Image(it.asImageBitmap(), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } ?: CircularProgressIndicator(color = c.onScrim)
        if (e.count > 1) CountBadge("${e.index + 1} / ${e.count}", Modifier.align(Alignment.BottomEnd))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.photoSlow) {
            Icon(Icons.Rounded.HourglassEmpty, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            Text("这台电视显示照片比较慢", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        } else {
            val parts = listOfNotNull(if (e.count > 1) "左右滑动切换" else null, if (showPrivacyNote) "照片已转正并去掉拍摄位置" else null)
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
    if (e.count > 1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = vm::previous, enabled = e.index > 0, modifier = Modifier.weight(1f).height(56.dp), colors = WscButtonColors.tonal()) {
                Icon(Icons.Rounded.SkipPrevious, null); Spacer(Modifier.width(6.dp)); Text("上一张")
            }
            FilledTonalButton(onClick = vm::next, enabled = e.index + 1 < e.count, modifier = Modifier.weight(1f).height(56.dp), colors = WscButtonColors.tonal()) {
                Text("下一张"); Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.SkipNext, null)
            }
        }
    }
}

/** P09 胶片条：当前项 primary 2 dp 外环，视频项带播放三角，点按跳转，随当前项自动滚动。 */
@Composable
private fun FilmStrip(s: PushSessionState, vm: PushViewModel) {
    val c = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    val current = s.engine.index
    LaunchedEffect(current) { state.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(s.items, key = { _, it -> it.index }) { i, it ->
            val selected = i == current
            Box(
                Modifier.size(56.dp)
                    .border(if (selected) BorderStroke(2.dp, c.primary) else BorderStroke(0.dp, c.surface), RoundedCornerShape(8.dp))
                    .padding(if (selected) 3.dp else 0.dp)
                    .background(c.scrim, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "切到这一项") { vm.select(i) }
                    .semantics {
                        this.selected = selected
                        contentDescription = "第 ${i + 1} 项，共 ${s.items.size} 项，${PushCopy.kindLabel(it.kind)}" + if (selected) "，当前" else ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (it.kind == MediaKind.AUDIO || it.remote) {
                    Icon(if (it.remote) Icons.Rounded.Link else Icons.Rounded.MusicNote, null, tint = c.onScrim)
                } else {
                    rememberThumbnail(it.uri, 160, 160)?.let { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (it.kind == MediaKind.VIDEO) Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = c.onScrim)
                }
            }
        }
    }
}

/** P05 连接中 / 加载中：三步进度 + 20 秒上限；10 秒起（本应用接收端一开始就）提示点“允许”。 */
@Composable
private fun LoadingCard(s: PushSessionState) {
    val c = MaterialTheme.colorScheme
    var shownAt by remember(s.engine.index, s.sessionId) { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var consentMs by remember(s.engine.index, s.sessionId) { mutableLongStateOf(0L) }
    LaunchedEffect(s.engine.index, s.sessionId) {
        shownAt = System.currentTimeMillis()
        var last = shownAt
        while (true) {
            delay(250)
            val t = System.currentTimeMillis()
            // 等对方点“允许”期间 20 秒预算暂停（与引擎一致，第 1 轮 EMU-1）
            if (s.engine.awaitingConsent) consentMs += t - last
            last = t
            now = t
        }
    }
    val waited = ((now - shownAt - consentMs) / 1000).coerceAtLeast(0)
    val photo = s.current?.kind == MediaKind.IMAGE
    AnimatedVisibility(now - shownAt >= 1_000, enter = fadeIn(tween(200))) {
        Column(
            Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val loading = s.engine.phase == PushPhase.LOADING
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Step(done = loading, active = !loading, text = if (loading) "已连接 ${s.target.name}" else "正在连接 ${s.target.name}…")
                if (photo) {
                    Step(done = false, active = loading, text = "正在发送到电视")
                } else {
                    Step(done = false, active = loading, text = if (s.engine.awaitingConsent) "等待 ${s.target.name} 允许…" else "电视正在加载…")
                    Step(done = false, active = false, text = "开始播放")
                }
            }
            LinearProgressIndicator(progress = { (waited / 20f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row {
                Text(
                    if (s.engine.awaitingConsent) "等待对方确认（不计入 20 秒）" else "已等待 $waited 秒",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Text("最长 20 秒", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            if (s.ownApp || s.engine.loadingSlow) {
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.SettingsRemote, null, Modifier.size(18.dp), tint = c.onSurface)
                    Text(
                        if (s.ownApp) "请在 ${s.target.name} 上点「允许」" else "如果电视上出现确认提示，请用遥控器点「允许」",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(done: Boolean, active: Boolean, text: String) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            done -> Box(Modifier.size(20.dp).background(ext.successContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = ext.onSuccessContainer)
            }
            active -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = c.outline)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (done || active) c.onSurface else c.onSurfaceVariant)
    }
}

/** 设备是音箱时把“电视”换成“设备”（交互稿 P07）。 */
private fun deviceWord(s: PushSessionState) = if (PushCopy.isAudioOnly(s.target.sink)) "设备" else "电视"

/** P06 / P07：进度与音量（能力缺失时只读 / 隐藏 + 同高说明，布局不塌陷）。 */
@Composable
private fun PlayerProgressAndVolume(s: PushSessionState, vm: PushViewModel) {
    val c = MaterialTheme.colorScheme
    val e = s.engine
    val device = deviceWord(s)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = e.durationMs
    val position = e.positionMs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            position == null -> InfoLine("这台${device}不提供播放进度")
            duration != null && duration > 0 && e.canSeek -> {
                val frac = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f)
                val shown = (frac * duration).toLong()
                if (dragging != null) Text(PushCopy.time(shown), fontFamily = FontFamily.Monospace, color = c.primary, style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = frac,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.seekTo((it * duration).toLong()) }; dragging = null },
                    modifier = Modifier.semantics {
                        contentDescription = "播放进度"
                        stateDescription = "${PushCopy.spokenTime(shown)}，共 ${PushCopy.spokenTime(duration)}"
                        // 读屏下按 10 秒步进调整（第 1 轮 UX P2-5 / PM P2-19）
                        customActions = listOf(
                            CustomAccessibilityAction("快进 10 秒") { vm.seekTo((position + 10_000).coerceAtMost(duration)); true },
                            CustomAccessibilityAction("后退 10 秒") { vm.seekTo((position - 10_000).coerceAtLeast(0)); true },
                        )
                    },
                )
                TimeRow(shown, duration)
            }
            else -> {
                LinearProgressIndicator(
                    progress = { if (duration != null && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "播放进度 ${PushCopy.spokenTime(position)}，共 ${PushCopy.spokenTime(duration)}，不能拖动"
                    },
                    color = c.outline,
                )
                TimeRow(position, duration)
                InfoLine("这台${device}不支持拖动进度")
            }
        }
        val vol = e.volume
        if (e.hasVolume && vol != null) {
            var local by remember { mutableFloatStateOf(vol.toFloat()) }
            var touching by remember { mutableStateOf(false) }
            LaunchedEffect(vol) { if (!touching) local = vol.toFloat() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.VolumeUp, null, tint = c.onSurfaceVariant)
                Slider(
                    value = local, valueRange = 0f..100f, steps = 19,
                    onValueChange = { touching = true; local = it; vm.setVolume(it.toInt()) },
                    onValueChangeFinished = { touching = false },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "${s.target.name} 音量" },
                )
                Text("${local.toInt()}", fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant, modifier = Modifier.widthIn(min = 32.dp))
            }
        } else {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.SettingsRemote, null, Modifier.size(18.dp), tint = c.onSurface)
                Text(
                    if (device == "设备") "请用设备自带的遥控器或按键调音量" else "请用电视遥控器调音量",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
                )
            }
        }
    }
}

/** 播放 / 暂停（72 dp）+ 上一个 / 下一个（48 dp）。不支持暂停时保留原位、显示不可用，点按提示。 */
@Composable
private fun PlayerButtons(s: PushSessionState, vm: PushViewModel, onMessage: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val e = s.engine
    val device = deviceWord(s)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (e.count > 1) {
            IconButton(onClick = vm::previous, enabled = e.index > 0, modifier = Modifier.size(48.dp), colors = WscButtonColors.icon()) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一个")
            }
        }
        val playing = e.phase == PushPhase.PLAYING
        val pauseUnavailable = playing && !e.canPause
        FilledIconButton(
            onClick = { if (pauseUnavailable) onMessage("这台${device}不支持暂停，可以结束推送") else vm.togglePlay() },
            modifier = Modifier.size(72.dp).semantics {
                contentDescription = if (playing) (if (e.canPause) "暂停" else "暂停，不可用，这台${device}不支持暂停") else "播放"
            },
            colors = if (pauseUnavailable) androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = c.surfaceContainer, contentColor = c.outline)
            else androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(),
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(36.dp)) }
        if (e.count > 1) {
            IconButton(onClick = vm::next, enabled = e.index + 1 < e.count, modifier = Modifier.size(48.dp), colors = WscButtonColors.icon()) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一个")
            }
        }
    }
}

@Composable
private fun TimeRow(position: Long?, duration: Long?) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Text(PushCopy.time(position), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(PushCopy.time(duration), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/** 等你选择转换方式（推送前提议，中性状态，第 1 轮 PM P1-8）。 */
@Composable
private fun AwaitingChoiceCard(onChoose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = c.onSurfaceVariant)
            Text("等你选择转换方式", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        }
        Text("电视可能放不了这个视频。选好后才会开始。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        FilledTonalButton(onClick = onChoose) { Text("选择") }
    }
}

/** P11 转换进度（列表时标题“正在转换第 n 项”）；按钮在页脚。 */
@Composable
private fun TranscodeCard(tvName: String, t: TranscodeUi) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val hot = t.phase == TranscodeUi.Phase.PAUSED_THERMAL
    val quarter = t.progress / 25 * 25
    Column(
        Modifier.fillMaxWidth().background(if (hot) ext.warningContainer else c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hot) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Thermostat, null, tint = ext.onWarningContainer)
                Text("手机温度较高，已暂停转换", style = MaterialTheme.typography.titleMedium, color = ext.onWarningContainer)
            }
            Text("降温后自动继续 · ${t.progress}%", style = MaterialTheme.typography.bodyMedium, color = ext.onWarningContainer)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (t.inPlaylist) "正在转换第 ${t.index + 1} 项" else "正在转换", style = MaterialTheme.typography.titleMedium,
                    color = c.onSurface, modifier = Modifier.weight(1f),
                )
                // 每 25% 播报一次，不逐个百分点播报（第 1 轮 UX P2-10）
                Text(
                    "${t.progress}%", fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = c.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "已转换 $quarter%" },
                )
            }
            LinearProgressIndicator(progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Row {
                Text(
                    PushCopy.minutes(t.etaMs)?.let { "约剩 ${it.removePrefix("约 ")}" } ?: "正在估算剩余时间",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (t.durationMs != null) Text(
                    "已转换 ${PushCopy.time(t.processedMs)} / ${PushCopy.time(t.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant,
                )
            }
            Text("转完自动推送到 $tvName，转换期间电视上不显示内容。可以锁屏或切到其他应用，转换不会中断。", style = MaterialTheme.typography.bodySmall, color = c.onSurface)
            InfoLine("手机温度过高时会自动暂停，降温后继续")
        }
    }
}

private fun failureIcon(error: PushError): ImageVector = when (error) {
    PushError.UNREACHABLE -> Icons.Rounded.LinkOff
    PushError.NO_NETWORK -> Icons.Rounded.WifiOff
    PushError.URL_FAILED -> Icons.Rounded.Link
    PushError.START_TIMEOUT -> Icons.Rounded.HourglassEmpty
    PushError.FILE_UNREADABLE, PushError.DECODE_FAILED -> Icons.Rounded.BrokenImage
    PushError.TRANSCODE_FAILED, PushError.NEEDS_TRANSCODE -> Icons.Rounded.SyncProblem
    PushError.REJECTED, PushError.UNSUPPORTED_FORMAT -> Icons.Rounded.Close
}

/** P13 失败卡：发生了什么 → 可能原因 → 下一步；出现时焦点移到标题（第 1 轮 UX P2-11）。 */
@Composable
private fun FailureCard(f: PushCopy.Failure, error: PushError, onAction: (PushCopy.Action) -> Unit) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(f.title) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(c.errorContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(failureIcon(error), null, tint = c.onErrorContainer)
            }
            Text(
                f.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                modifier = Modifier.focusRequester(focus).focusable().semantics { heading(); liveRegion = LiveRegionMode.Assertive },
            )
        }
        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Button(onClick = { onAction(f.primary) }, Modifier.fillMaxWidth()) { Text(f.primary.label) }
        f.secondary?.let { FilledTonalButton(onClick = { onAction(it) }, Modifier.fillMaxWidth()) { Text(it.label) } }
        f.tertiary?.let { TextButton(onClick = { onAction(it) }, Modifier.fillMaxWidth()) { Text(it.label) } }
    }
}

