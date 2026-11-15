package com.benchan.wireless.screencasting.feature.sender.share

import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.asString
import com.benchan.wireless.screencasting.feature.sender.ui.uiText
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.ui.theme.ElderDimens
import com.benchan.wireless.screencasting.core.ui.theme.LocalElderTypography
import com.benchan.wireless.screencasting.core.ui.theme.ProvideElderTypography
import com.benchan.wireless.screencasting.core.ui.theme.ScrimAlpha
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.sender.elder.ElderButton
import com.benchan.wireless.screencasting.feature.sender.elder.ElderButtonKind
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushConfirmDialog
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushMutexDialog
import com.benchan.wireless.screencasting.feature.sender.ui.push.TranscodeOfferSheet
import com.benchan.wireless.screencasting.feature.sender.ui.push.UnencryptedInfoDialog
import com.benchan.wireless.screencasting.feature.sender.ui.push.rememberThumbnail
import com.benchan.wireless.screencasting.feature.settings.InstallHelpScreen
import com.benchan.wireless.screencasting.feature.settings.NetworkDiagnosticsScreen
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import kotlinx.coroutines.delay

private enum class ShareSub { NONE, DIAGNOSTICS, INSTALL }

/**
 * 分享页（交互稿 S01–S08）：盖在来源 App 上的底部面板，遮罩只压暗 32%；下滑、点遮罩、✕、返回 = 放弃（未推送时）。
 * 长辈模式（F24）用大字版：按钮一律 [ElderButton]（≥64 dp、只用 on* 颜色），字号取 [LocalElderTypography]，
 * 不显示协议名，只列家人设置的电视和已经确认过的电视。
 */
@Composable
internal fun ShareScreen(vm: ShareViewModel, snackbar: SnackbarHostState, elder: Boolean, onClose: () -> Unit) {
    var sub by rememberSaveable { mutableStateOf(ShareSub.NONE) }
    when (sub) {
        // 诊断 / 安装帮助在分享自己的任务里打开，返回后分享内容和准备进度都在（交互稿 S06）
        ShareSub.DIAGNOSTICS -> {
            BackHandler { sub = ShareSub.NONE }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) { NetworkDiagnosticsScreen(onBack = { sub = ShareSub.NONE }) }
            return
        }
        ShareSub.INSTALL -> {
            BackHandler { sub = ShareSub.NONE }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) { InstallHelpScreen(onBack = { sub = ShareSub.NONE }) }
            return
        }
        ShareSub.NONE -> Unit
    }
    val content = @Composable {
        SharePanel(vm, snackbar, elder, onClose, onDiagnostics = { sub = ShareSub.DIAGNOSTICS }, onInstall = { sub = ShareSub.INSTALL })
    }
    if (elder) ProvideElderTypography(content) else content()
}

// ------------------------------------------------------------------ 样式辅助（长辈 / 普通两套）

@Composable
private fun body(elder: Boolean): TextStyle = if (elder) LocalElderTypography.current.body else MaterialTheme.typography.bodyMedium

@Composable
private fun small(elder: Boolean): TextStyle = if (elder) LocalElderTypography.current.bodySmall else MaterialTheme.typography.bodySmall

@Composable
private fun titleStyle(elder: Boolean): TextStyle = if (elder) LocalElderTypography.current.headline else MaterialTheme.typography.titleLarge

@Composable
private fun rowTitle(elder: Boolean): TextStyle = if (elder) LocalElderTypography.current.label else MaterialTheme.typography.bodyLarge

@Composable
private fun groupStyle(elder: Boolean): TextStyle = if (elder) LocalElderTypography.current.bodySmall else MaterialTheme.typography.titleSmall

private enum class BtnKind { PRIMARY, TONAL, OUTLINED, TEXT }

/** 分享页按钮：长辈版用 [ElderButton]；普通版整宽、≥48 dp。 */
@Composable
private fun ShareBtn(text: String, onClick: () -> Unit, elder: Boolean, kind: BtnKind = BtnKind.PRIMARY, spoken: String? = null) {
    if (elder) {
        ElderButton(
            text, onClick,
            kind = when (kind) {
                BtnKind.PRIMARY -> ElderButtonKind.PRIMARY
                BtnKind.TONAL -> ElderButtonKind.SECONDARY
                BtnKind.OUTLINED -> ElderButtonKind.OUTLINED
                BtnKind.TEXT -> ElderButtonKind.TEXT
            },
            spoken = spoken,
        )
        return
    }
    val m = Modifier.fillMaxWidth().heightIn(min = 48.dp).let { b -> spoken?.let { s -> b.semantics { contentDescription = s } } ?: b }
    when (kind) {
        BtnKind.PRIMARY -> Button(onClick = onClick, modifier = m) { Text(text) }
        BtnKind.TONAL -> FilledTonalButton(onClick = onClick, modifier = m) { Text(text) }
        BtnKind.OUTLINED -> OutlinedButton(onClick = onClick, modifier = m) { Text(text) }
        BtnKind.TEXT -> TextButton(onClick = onClick, modifier = m) { Text(text) }
    }
}

private fun View.announce(text: String) = announceForAccessibility(text)

// ------------------------------------------------------------------ 面板

@Composable
private fun SharePanel(vm: ShareViewModel, snackbar: SnackbarHostState, elder: Boolean, onClose: () -> Unit, onDiagnostics: () -> Unit, onInstall: () -> Unit) {
    val u by vm.ui.collectAsStateWithLifecycle()
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { d -> drag = (drag + d).coerceAtLeast(0f) }
    val started = u.stage == ShareStage.STARTING || u.stage == ShareStage.STARTED
    val paneTitleText = stringResource(R.string.share_title)
    // 推送前返回 = 放弃；S02 返回 = 换一台
    BackHandler {
        if (u.stage == ShareStage.STARTING) vm.changeDevice() else onClose()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 面板最高到状态栏下 64 dp（按实际状态栏高度计算，UX P2-2）
        val statusBar = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val maxHeight = maxHeight - statusBar - 64.dp
        // 遮罩只响应触摸，不进读屏（✕ 才是可见的替代，UX P1-14）
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha.Dim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .clearAndSetSemantics { },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight)
                .offset { IntOffset(0, drag.toInt()) }
                .background(c.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .semantics { paneTitle = paneTitleText }
                .navigationBarsPadding()
                .animateContentSize(),
        ) {
            // 拖动区：整宽 × 48 dp（含标题行），下滑超过 120 dp = 关闭；✕ 是它的可见替代
            Column(
                Modifier.fillMaxWidth().draggable(
                    dragState, Orientation.Vertical,
                    onDragStopped = { if (drag > with(density) { 120.dp.toPx() }) onClose() else drag = 0f },
                ),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).background(c.outlineVariant, RoundedCornerShape(2.dp)))
                }
                Header(u, vm, onClose, elder, started)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (elder) 12.dp else 4.dp),
            ) {
                when (u.stage) {
                    ShareStage.CHECKING -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.share_checking), style = body(elder))
                    }
                    ShareStage.BLOCKED -> u.block?.let { BlockBody(it, u, vm, elder) }
                    ShareStage.WEB_URL -> WebUrlBody(u, vm, elder)
                    ShareStage.DIRECT_FINDING -> DirectFinding(u, elder)
                    ShareStage.PICK -> PickContent(u, vm, elder, onDiagnostics, onInstall)
                    ShareStage.STARTING -> StartingContent(u, vm, elder)
                    ShareStage.STARTED -> StartedContent(u, vm, elder)
                }
            }
            // 主按钮固定在面板底部，不随内容滚走（交互稿“200% 字号要点”，UX P1-13）
            Footer(u, vm, elder, onClose, onDiagnostics, onInstall)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }
    ShareDialogs(vm, elder)
    // 转换提议（P10，面板内容之上的底部面板）
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session?.takeIf { it.sessionId == u.sessionId && it.isActive }
    val offer = s?.transcode?.takeIf { it.phase == TranscodeUi.Phase.OFFER || it.phase == TranscodeUi.Phase.NO_SPACE || it.phase == TranscodeUi.Phase.PHONE_UNSUPPORTED }
    if (offer != null && u.stage == ShareStage.STARTING) {
        TranscodeOfferSheet(
            offer, s.target.name, s.items.getOrNull(offer.index)?.title.orEmpty(),
            onTranscode = { videoOnly, auto -> vm.acceptTranscode(videoOnly, auto) },
            onTryOriginal = vm::tryOriginal,
            onChangeFile = { vm.dismissTranscodeOffer(); vm.openRemote() },
            onDismiss = vm::dismissTranscodeOffer,
        )
    }
}

@Composable
private fun Header(u: ShareUi, vm: ShareViewModel, onClose: () -> Unit, elder: Boolean, started: Boolean) {
    val title = when (u.stage) {
        ShareStage.STARTING -> stringResource(R.string.share_header_push_to, u.target?.name.orEmpty())
        ShareStage.WEB_URL -> stringResource(R.string.share_web_title)
        ShareStage.STARTED -> null
        else -> stringResource(if (u.reselect) R.string.share_reselect_title else R.string.share_title)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(u.stage) { runCatching { focus.requestFocus() } }
    val iconSize = if (elder) 64.dp else 48.dp
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (u.stage == ShareStage.STARTING) {
            IconButton(onClick = vm::changeDevice, Modifier.size(iconSize)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.share_change_tv)) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        if (title != null) {
            Text(title, Modifier.weight(1f).focusRequester(focus).focusable().semantics { heading() }, style = titleStyle(elder))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (u.stage != ShareStage.STARTING) {
            // 推送开始后点 ✕ 只是关闭面板，推送继续（UX P1-14）
            IconButton(onClick = onClose, Modifier.size(iconSize)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(if (started) R.string.share_close_continue else R.string.share_close))
            }
        }
    }
}

/** 各阶段固定在底部的主操作。 */
@Composable
private fun Footer(u: ShareUi, vm: ShareViewModel, elder: Boolean, onClose: () -> Unit, onDiagnostics: () -> Unit, onInstall: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val buttons: (@Composable ColumnScope.() -> Unit)? = when (u.stage) {
        ShareStage.BLOCKED -> u.block?.let { b -> { BlockButtons(b, vm, elder, onClose) } }
        ShareStage.WEB_URL -> ({ WebUrlButtons(vm, elder, onClose) })
        ShareStage.DIRECT_FINDING -> ({
            ShareBtn(stringResource(R.string.share_pick_other_tv), vm::cancelDirect, elder, BtnKind.TONAL)
            ShareBtn(stringResource(R.string.share_cancel), onClose, elder, BtnKind.TEXT)
        })
        ShareStage.STARTING -> {
            val s = session?.takeIf { it.sessionId == u.sessionId }
            val failure = s?.let(::failureOf)
            if (s != null && failure != null) {
                ({ FailureButtons(failure, s, u, vm, elder, onDiagnostics, onInstall) })
            } else {
                ({ ShareBtn(stringResource(R.string.share_cancel_push), vm::cancelPush, elder, BtnKind.OUTLINED) })
            }
        }
        ShareStage.STARTED -> ({
            ShareBtn(stringResource(R.string.share_done), onClose, elder)
            ShareBtn(stringResource(R.string.share_open_remote), vm::openRemote, elder, BtnKind.TONAL)
        })
        else -> null
    }
    buttons?.let {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(if (elder) ElderDimens.Gap else 8.dp), content = it)
    }
}

// ------------------------------------------------------------------ 摘要与提示

@Composable
private fun Summary(u: ShareUi, vm: ShareViewModel, elder: Boolean) {
    val c = MaterialTheme.colorScheme
    val url = u.content.url
    if (url != null) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).background(c.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Link, null, tint = c.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            // 单行中间省略（UX P2-19）
            Text(url, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (u.stage == ShareStage.PICK) {
                TextButton(onClick = vm::editUrl, Modifier.heightIn(min = if (elder) 64.dp else 48.dp)) { Text(stringResource(R.string.share_edit), color = c.onSurface, style = rowTitle(elder)) }
            }
        }
        return
    }
    val items = u.content.items
    if (items.isEmpty()) return
    val progress by vm.progress.collectAsStateWithLifecycle()
    val prep = prepLine(items, progress)?.asString()
    val summary = summaryText(items.map { it.kind })
    val separator = stringResource(R.string.share_spoken_separator)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clearAndSetSemantics {
            contentDescription = ShareCopy.spoken(listOfNotNull(summary, prep).joinToString(separator), separator)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fontScale = LocalDensity.current.fontScale
        val shown = if (fontScale >= 1.5f) 2 else 4
        items.take(shown).forEachIndexed { i, item ->
            Thumb(item, extra = if (i == shown - 1 && items.size > shown) items.size - shown + 1 else 0)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(summary, style = rowTitle(elder))
            if (items.size > 1) Text(stringResource(R.string.share_play_in_order), style = small(elder), color = c.onSurfaceVariant)
        }
    }
}

/** 摘要下方的准备进度；失败项单独计数（PM P2-3）。 */
private fun prepLine(items: List<SharedItem>, progress: List<ItemProgress>): UiText? {
    if (progress.isEmpty() || items.none { it.mode == ReadMode.COPY }) return null
    val active = progress.withIndex().filter { items.getOrNull(it.index)?.mode == ReadMode.COPY && it.value.phase != ItemPhase.SKIPPED }
    val failed = active.count { it.value.phase == ItemPhase.FAILED }
    if (active.all { it.value.phase == ItemPhase.READY || it.value.phase == ItemPhase.FAILED }) {
        return if (failed > 0) ShareCopy.notReady(failed) else uiText(R.string.share_prepared)
    }
    val total = active.sumOf { items[it.index].sizeBytes ?: 0L }.takeIf { active.all { a -> items[a.index].sizeBytes != null } }
    val copied = active.sumOf { if (it.value.phase == ItemPhase.READY) items[it.index].sizeBytes ?: it.value.copiedBytes else it.value.copiedBytes }
    return ShareCopy.preparing(copied, total)
}

@Composable
private fun Thumb(item: SharedItem, extra: Int) {
    val c = MaterialTheme.colorScheme
    val bmp = rememberThumbnail(item.source, 96, 96)
    Box(Modifier.size(48.dp).background(c.surfaceContainerHighest, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (item.kind == MediaKind.VIDEO) Icons.Rounded.PlayArrow else if (item.kind == MediaKind.AUDIO) Icons.Rounded.MusicNote else Icons.Rounded.InsertDriveFile, null, tint = c.onSurfaceVariant)
        if (item.kind == MediaKind.VIDEO && bmp != null) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
        if (extra > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("+$extra", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun InfoBar(title: String, detail: String?, elder: Boolean, icon: ImageVector = Icons.Rounded.Info) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp).semantics(mergeDescendants = true) {}) {
        Icon(icon, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = body(elder), color = c.onSurface)
            detail?.let { Text(it, style = small(elder), color = c.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Notices(u: ShareUi, elder: Boolean) {
    val cnt = u.content
    if (cnt.ignored > 0) ShareCopy.ignored(cnt.ignored).let { InfoBar(it.first.asString(), it.second.asString(), elder) }
    if (cnt.unreadable > 0) ShareCopy.unreadable(cnt.unreadable, cnt.unreadableReason).let { InfoBar(it.first.asString(), it.second.asString(), elder, Icons.Rounded.InsertDriveFile) }
    if (cnt.truncated) InfoBar(stringResource(R.string.share_truncated), null, elder)
    u.directMissed?.let { InfoBar(it.asString(), null, elder) }
}

@Composable
private fun PrepRow(u: ShareUi, vm: ShareViewModel, elder: Boolean) {
    val items = u.content.items
    val progress by vm.progress.collectAsStateWithLifecycle()
    val view = LocalView.current
    if (items.none { it.mode == ReadMode.COPY } || progress.isEmpty()) return
    // 0–1 s 不显示（避免一闪而过）；完成后 2 s 收起
    val visibleAfter = remember { System.currentTimeMillis() + 1_000 }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val line = prepLine(items, progress)?.asString() ?: return
    val done = progress.withIndex().filter { items.getOrNull(it.index)?.mode == ReadMode.COPY }.all { it.value.phase != ItemPhase.WAITING && it.value.phase != ItemPhase.COPYING }
    var doneAt by remember { mutableLongStateOf(0L) }
    if (done && doneAt == 0L) doneAt = now
    val active = progress.withIndex().filter { items.getOrNull(it.index)?.mode == ReadMode.COPY && it.value.phase != ItemPhase.SKIPPED }
    val total = active.sumOf { items[it.index].sizeBytes ?: 0L }
    val copied = active.sumOf { if (it.value.phase == ItemPhase.READY) items[it.index].sizeBytes ?: 0L else it.value.copiedBytes }
    val pct = ShareCopy.percent(copied, total.takeIf { it > 0 })
    // 读屏：每 25% 播报一次，完成播报一次（UX P2-7）
    var lastStep by remember { mutableIntStateOf(-1) }
    val res = LocalResources.current
    LaunchedEffect(ShareCopy.announceStep(pct), done) {
        val step = if (done) 100 else ShareCopy.announceStep(pct) ?: return@LaunchedEffect
        if (step != lastStep && step > 0) {
            lastStep = step
            view.announce(if (done) line else res.getString(R.string.share_preparing_percent, step))
        }
    }
    if (now < visibleAfter && !done) return
    if (done && doneAt > 0 && now - doneAt > 2_000) return
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = line }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (done) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = WscTheme.extended.success)
            Text(line, Modifier.weight(1f).padding(start = if (done) 6.dp else 0.dp), style = body(elder))
            if (!done) Text(stringResource(R.string.share_auto_start), style = small(elder), color = c.onSurfaceVariant)
        }
        if (!done) {
            if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ S01 选设备

@Composable
private fun PickContent(u: ShareUi, vm: ShareViewModel, elder: Boolean, onDiagnostics: () -> Unit, onInstall: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val lan by vm.lan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val mirrorSt by vm.mirrorState.collectAsStateWithLifecycle()
    val view = LocalView.current
    var unencryptedInfo by remember { mutableStateOf(false) }
    ConflictBanner(session?.takeIf { it.isActive }, mirrorSt, elder)
    Summary(u, vm, elder)
    Notices(u, elder)
    PrepRow(u, vm, elder)
    if (settings?.shareIntroDismissed == false && vm.neverPushed && !elder) {
        val (t, d) = ShareCopy.firstUseNote(vm.transferVideo).let { it.first.asString() to it.second.asString() }
        val c = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(c.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(t, style = MaterialTheme.typography.bodyMedium)
            Text(d, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            TextButton(onClick = { vm.dismissIntro() }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.share_got_it), color = c.onSurface) }
        }
    }
    if (lan == null) {
        EmptyState(Icons.Rounded.WifiOff, stringResource(R.string.share_no_wifi_title), stringResource(R.string.share_no_wifi_detail), elder) {
            val ctx = LocalContext.current
            ShareBtn(stringResource(R.string.share_open_wifi), { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, elder)
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val elapsed = (now - vm.searchStartedAt).coerceAtLeast(0)
    val pushable = rows.count { it.enabled }
    // 从空态自动换回列表时播报（UX P2-8）
    var wasEmpty by remember { mutableStateOf(false) }
    val res = LocalResources.current
    LaunchedEffect(pushable > 0) {
        if (pushable > 0 && wasEmpty) view.announce(res.getQuantityString(R.plurals.share_found_tvs, pushable, pushable))
        wasEmpty = pushable == 0
    }
    if (pushable == 0 && elapsed >= 10_000) {
        // S06：任何时候发现设备都会自动换回列表
        EmptyState(Icons.Rounded.Tv, stringResource(R.string.share_empty_title), stringResource(R.string.share_empty_detail), elder) {
            ShareBtn(stringResource(R.string.share_rescan), vm::restartDiscovery, elder)
            ShareBtn(stringResource(R.string.share_diagnostics), onDiagnostics, elder, BtnKind.TONAL)
            if (!elder) ShareBtn(stringResource(R.string.share_no_cast_feature), onInstall, elder, BtnKind.TEXT)
        }
        rows.filter { !it.enabled }.forEach { DeviceRowView(it, elder) {} }
        if (elder) InfoBar(stringResource(R.string.share_elder_new_tv), null, elder)
        return
    }
    val groups = rows.groupBy { it.group }
    var first = true
    for (g in ShareGroup.entries) {
        val list = groups[g].orEmpty()
        if (list.isEmpty()) continue
        val title = when (g) {
            ShareGroup.FAMILY -> R.string.share_group_family
            ShareGroup.RECENT -> R.string.share_group_recent
            ShareGroup.DLNA -> if (elder) null else R.string.share_group_dlna
            ShareGroup.OWN -> if (elder) null else R.string.share_group_own
            ShareGroup.DISABLED -> null
        }?.let { stringResource(it) }
        title?.let {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f).semantics { heading() }, style = groupStyle(elder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (g == ShareGroup.DLNA) {
                    // “ⓘ 不加密”可点，说明弹层同 P01（UX P2-10）
                    TextButton(onClick = { unencryptedInfo = true }, Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.share_unencrypted), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 前 10 秒在第一个分组标题右侧显示“仍在搜索”（UX P2-12）
                if (first && elapsed < 10_000) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share_searching_short), style = small(elder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            first = false
        }
        list.forEach { row -> DeviceRowView(row, elder) { vm.select(row) } }
    }
    if (pushable == 0) repeat(3) { SkeletonRow() }
    if (elapsed >= 3_000 && pushable == 0) {
        Text(stringResource(R.string.share_still_searching), Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = small(elder), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // 长辈模式只列家人设置 / 确认过的电视，给一句出路
    if (elder) InfoBar(stringResource(R.string.share_elder_new_tv), null, elder)
    if (unencryptedInfo) UnencryptedInfoDialog { unencryptedInfo = false }
}

@Composable
private fun ConflictBanner(push: PushSessionState?, mirrorSt: SenderState, elder: Boolean) {
    val c = MaterialTheme.colorScheme
    val text = when {
        push != null -> ShareCopy.bannerPushing(push.target.name, PushCopy.bannerStatus(push))
        mirrorSt !is SenderState.Idle && !mirrorSt.isTerminal -> ShareCopy.bannerMirroring(mirrorTargetName(mirrorSt) ?: uiText(R.string.share_tv))
        else -> null
    } ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(c.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp).semantics(mergeDescendants = true) {}) {
        Text(text.first.asString(), style = body(elder), color = c.onPrimaryContainer)
        Text(text.second.asString(), style = small(elder), color = c.onPrimaryContainer)
    }
}

internal fun mirrorTargetName(s: SenderState): String? = when (s) {
    is SenderState.Casting -> s.target.name
    is SenderState.Reconnecting -> s.target.name
    is SenderState.Starting -> s.target.name
    is SenderState.Connecting -> s.target.name
    is SenderState.AwaitingPin -> s.target.name
    is SenderState.AwaitingTvConfirm -> s.target.name
    is SenderState.ReadyToCapture -> s.target.name
    else -> null
}

@Composable
private fun DeviceRowView(row: ShareRow, elder: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val titleColor = if (row.enabled) c.onSurface else c.outline
    val subColor = if (row.enabled) c.onSurfaceVariant else c.outline
    val subtitle = row.subtitle.asString()
    val separator = stringResource(R.string.share_spoken_separator)
    val spoken = listOfNotNull(row.name, if (!row.enabled) stringResource(R.string.share_unavailable) else null, subtitle).joinToString(separator)
    val pushingState = stringResource(R.string.share_pushing_state)
    // 置灰行可聚焦、读出原因，但不可点（没有水波纹，读屏不说“双击以激活”，UX P2-11）
    val interaction = if (row.enabled) Modifier.clickable(onClick = onClick) else Modifier.focusable()
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (elder) ElderDimens.Row else 64.dp).then(interaction)
            .clearAndSetSemantics { contentDescription = spoken; if (row.pushing) stateDescription = pushingState }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            row.audioOnly -> Icons.Rounded.MusicNote
            row.ownApp -> Icons.Rounded.ScreenShare
            else -> Icons.Rounded.PlayCircleOutline
        }
        val (bg, fg) = when {
            !row.enabled -> c.surfaceContainerHigh to c.outline
            row.group == ShareGroup.RECENT || row.group == ShareGroup.FAMILY -> c.primaryContainer to c.onPrimaryContainer
            else -> c.surfaceContainerHigh to c.onSurfaceVariant
        }
        Box(Modifier.size(40.dp).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = fg) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = rowTitle(elder), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = small(elder), color = subColor)
        }
        // 长辈版不显示协议名
        if (!elder) {
            Text(
                if (row.ownApp) "WSC" else "DLNA",
                Modifier.background(c.surfaceContainerHighest, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = c.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkeletonRow() {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(c.surfaceContainerHighest, RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Box(Modifier.size(140.dp, 14.dp).background(c.surfaceContainerHighest, RoundedCornerShape(4.dp)))
            Spacer(Modifier.size(6.dp))
            Box(Modifier.size(90.dp, 10.dp).background(c.surfaceContainerHighest, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, detail: String, elder: Boolean, actions: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (elder) 12.dp else 8.dp),
    ) {
        Box(Modifier.size(64.dp).background(c.surfaceContainerHigh, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.onSurfaceVariant)
        }
        Text(title, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Assertive }, style = if (elder) LocalElderTypography.current.title else MaterialTheme.typography.titleMedium)
        Text(detail, style = body(elder), color = c.onSurfaceVariant)
        actions()
    }
}

// ------------------------------------------------------------------ S05 结果卡

@Composable
private fun ResultCard(title: String, detail: String, elder: Boolean) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(c.surfaceContainer, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = c.error)
            Spacer(Modifier.width(8.dp))
            Text(title, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Assertive }, style = if (elder) LocalElderTypography.current.title else MaterialTheme.typography.titleMedium)
        }
        Text(detail, style = body(elder), color = c.onSurfaceVariant)
    }
}

@Composable
private fun BlockBody(block: ShareBlock, u: ShareUi, vm: ShareViewModel, elder: Boolean) {
    when (block) {
        ShareBlock.NothingSupported -> ResultCard(stringResource(R.string.share_nothing_title), stringResource(R.string.share_nothing_detail), elder)
        is ShareBlock.Unreadable -> ResultCard(
            stringResource(R.string.share_unreadable_block_title),
            stringResource(R.string.share_unreadable_block_detail, ShareCopy.unreadableDetail(block.reason).asString()),
            elder,
        )
        is ShareBlock.NoSpace -> {
            val p = block.plan
            Summary(u, vm, elder)
            ResultCard(stringResource(R.string.share_no_space_title), ShareCopy.noSpaceDetail(p.total, p.requiredBytes, p.availableBytes - ShareLimits.RESERVE_BYTES).asString(), elder)
        }
        ShareBlock.NoUrl -> ResultCard(stringResource(R.string.share_no_url_title), stringResource(R.string.share_no_url_detail), elder)
        ShareBlock.PrepareFailed -> ResultCard(stringResource(R.string.share_prepare_failed_title), stringResource(R.string.share_prepare_failed_detail), elder)
    }
}

/** 结果卡按钮：纵向排列，主按钮在上。 */
@Composable
private fun BlockButtons(block: ShareBlock, vm: ShareViewModel, elder: Boolean, onClose: () -> Unit) {
    when (block) {
        ShareBlock.NothingSupported -> {
            ShareBtn(stringResource(R.string.share_got_it), onClose, elder)
            ShareBtn(stringResource(R.string.share_web_mirror), vm::useMirror, elder, BtnKind.OUTLINED)
        }
        is ShareBlock.Unreadable -> {
            if (!elder) ShareBtn(stringResource(R.string.share_reselect_in_app), vm::reselectInApp, elder)
            ShareBtn(stringResource(if (elder) R.string.share_got_it else R.string.share_cancel), onClose, elder, if (elder) BtnKind.PRIMARY else BtnKind.OUTLINED)
        }
        is ShareBlock.NoSpace -> {
            val p = block.plan
            if (p.fitCount > 0) ShareBtn(ShareCopy.onlyFirst(p.fitCount, p.fitBytes).asString(), vm::onlyFirst, elder)
            ShareBtn(stringResource(R.string.share_web_mirror), vm::useMirror, elder, BtnKind.OUTLINED)
            ShareBtn(stringResource(R.string.share_cancel), onClose, elder, BtnKind.TEXT)
        }
        ShareBlock.NoUrl -> ShareBtn(stringResource(R.string.share_got_it), onClose, elder)
        ShareBlock.PrepareFailed -> {
            ShareBtn(stringResource(R.string.share_got_it), onClose, elder)
            ShareBtn(stringResource(R.string.share_web_mirror), vm::useMirror, elder, BtnKind.OUTLINED)
        }
    }
}

// ------------------------------------------------------------------ S04 网页地址

@Composable
private fun WebUrlBody(u: ShareUi, vm: ShareViewModel, elder: Boolean) {
    val c = MaterialTheme.colorScheme
    val mirrorSt by vm.mirrorState.collectAsStateWithLifecycle()
    val mirroringTo = mirrorTargetName(mirrorSt)?.takeIf { mirrorSt !is SenderState.Idle && !mirrorSt.isTerminal }
    val urlCd = stringResource(R.string.share_url_cd, u.content.url.orEmpty().toCharArray().joinToString(" "))
    Text(
        u.content.url.orEmpty(),
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(c.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp)
            .semantics { contentDescription = urlCd },
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = c.onSurface,
    )
    Text(mirroringTo?.let { ShareCopy.webMirroring(it).asString() } ?: stringResource(R.string.share_web_detail), style = body(elder), color = c.onSurface)
}

@Composable
private fun WebUrlButtons(vm: ShareViewModel, elder: Boolean, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val mirrorSt by vm.mirrorState.collectAsStateWithLifecycle()
    val mirroring = mirrorSt !is SenderState.Idle && !mirrorSt.isTerminal
    val sep = stringResource(R.string.share_spoken_separator)
    val mirrorLabel = stringResource(R.string.share_web_mirror)
    val mirrorNote = stringResource(R.string.share_web_mirror_note)
    val pushLabel = stringResource(R.string.share_web_push)
    val pushNote = stringResource(R.string.share_web_push_note)
    if (mirroring) {
        ShareBtn(stringResource(R.string.share_got_it), onClose, elder)
    } else {
        ShareBtn(mirrorLabel, vm::useMirror, elder, spoken = mirrorLabel + sep + mirrorNote)
        // 说明已经包含在按钮的读屏描述里，不再单独读一遍（UX P2-14）
        Text(mirrorNote, Modifier.clearAndSetSemantics { }, style = small(elder), color = c.onSurfaceVariant)
    }
    ShareBtn(pushLabel, vm::pushWebAnyway, elder, if (mirroring) BtnKind.TONAL else BtnKind.OUTLINED, spoken = pushLabel + sep + pushNote)
    Text(pushNote, Modifier.clearAndSetSemantics { }, style = small(elder), color = c.onSurfaceVariant)
}

// ------------------------------------------------------------------ S08 Direct Share 紧凑面板

@Composable
private fun DirectFinding(u: ShareUi, elder: Boolean) {
    val c = MaterialTheme.colorScheme
    Notices(u, elder)
    Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(ShareCopy.directFinding(u.directName ?: stringResource(R.string.share_tv)).asString(), style = rowTitle(elder))
            Text(stringResource(R.string.share_direct_hint), style = small(elder), color = c.onSurfaceVariant)
        }
    }
    // 5 秒进度（UX P2-17）
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (progress < 1f) {
            progress = ((System.currentTimeMillis() - start) / 5_000f).coerceAtMost(1f)
            delay(100)
        }
    }
    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().clearAndSetSemantics { })
}

// ------------------------------------------------------------------ S02 准备 → 连接 → 播放

@Composable
private fun StartingContent(u: ShareUi, vm: ShareViewModel, elder: Boolean) {
    val session by vm.session.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val s = session?.takeIf { it.sessionId == u.sessionId }
    val tv = u.target?.name ?: stringResource(R.string.share_tv)
    val c = MaterialTheme.colorScheme
    Summary(u, vm, elder)
    // 失败：换成 P13 对应失败卡（紧凑版）；按钮在页脚
    val failure = s?.let(::failureOf)
    if (s != null && failure != null) {
        // 分享副本复制失败：说具体原因，不说“手机收回了读取授权”（PM P1-8）
        val copyFail = if (failure == PushError.FILE_UNREADABLE) vm.copyFailureFor(s) else null
        val (title, detail) = copyFail?.let { ShareCopy.copyFailure(it).let { (t, d) -> t.asString() to d.asString() } }
            ?: pushFailureTexts(failure, tv, s)
        ResultCard(title, detail, elder)
        return
    }
    val first = progress.firstOrNull()
    val firstItem = u.content.items.firstOrNull()
    val prepDone = u.content.url != null || first == null || first.phase == ItemPhase.READY || first.phase == ItemPhase.FAILED || first.phase == ItemPhase.SKIPPED
    val phase = s?.engine?.phase
    val connecting = prepDone && (phase == null || phase == PushPhase.PREPARING || phase == PushPhase.IDLE)
    val loading = phase == PushPhase.LOADING
    val transcoding = s?.transcode?.takeIf { it.phase == TranscodeUi.Phase.RUNNING || it.phase == TranscodeUi.Phase.PAUSED_THERMAL }
    // 剩余时间：按复制速度估算，只在跨档时变化（UX P2-7）
    val startedAt = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (u.content.url == null) {
            Step(prepDone, !prepDone, ShareCopy.preparingFirst(u.content.items.size > 1).asString(), elder)
            if (!prepDone && first != null) {
                val pct = ShareCopy.percent(first.copiedBytes, firstItem?.sizeBytes)
                Text(pct?.let { "$it %" } ?: ShareCopy.bytes(first.copiedBytes, null).asString(), Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                if (pct != null) {
                    LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth())
                    val elapsed = System.currentTimeMillis() - startedAt
                    val eta = if (pct in 3..99 && elapsed > 1_000) elapsed * (100 - pct) / pct else null
                    Row(Modifier.fillMaxWidth()) {
                        Text(ShareCopy.bytes(first.copiedBytes, firstItem?.sizeBytes).asString(), Modifier.weight(1f), style = small(elder), color = c.onSurfaceVariant)
                        ShareCopy.remaining(eta)?.let { Text(it.asString(), style = small(elder), color = c.onSurfaceVariant) }
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        Step(loading || phase == PushPhase.PLAYING, connecting, ShareCopy.connectStep(tv).asString(), elder)
        Step(false, loading, stringResource(if (loading) R.string.share_tv_loading else R.string.share_play_step), elder)
        transcoding?.let { Text(stringResource(R.string.share_transcoding, it.progress), style = body(elder)) }
        if (s?.ownApp == true || s?.engine?.loadingSlow == true) {
            Text(stringResource(R.string.share_allow_hint), style = body(elder), color = c.onSurface)
        }
        Text(stringResource(R.string.share_leave_note), style = small(elder), color = c.onSurfaceVariant)
    }
}

/** 失败卡的按钮（分享路径的取舍：网址失败固定再给一次 [改用屏幕镜像]，[修改网址] 在分享页里改，PM P1-7）。 */
@Composable
private fun FailureButtons(error: PushError, s: PushSessionState, u: ShareUi, vm: ShareViewModel, elder: Boolean, onDiagnostics: () -> Unit, onInstall: () -> Unit) {
    val copyFail = if (error == PushError.FILE_UNREADABLE) vm.copyFailureFor(s) else null
    val actions: List<PushCopy.Action> = when {
        copyFail != null -> listOf(PushCopy.Action.OTHER_DEVICE, PushCopy.Action.USE_MIRROR)
        error == PushError.URL_FAILED -> listOf(PushCopy.Action.EDIT_URL, PushCopy.Action.USE_MIRROR)
        else -> pushFailureActions(error, u.target?.name ?: stringResource(R.string.share_tv), s)
    }.let { a -> if (elder) a.filter { it != PushCopy.Action.INSTALL_APP && it != PushCopy.Action.DIAGNOSTICS }.ifEmpty { listOf(PushCopy.Action.OTHER_DEVICE) } else a }
    actions.forEachIndexed { i, a ->
        ShareBtn(actionLabel(a), { onFailureAction(a, vm, onDiagnostics, onInstall) }, elder, if (i == 0) BtnKind.PRIMARY else BtnKind.OUTLINED)
    }
}

private fun failureOf(s: PushSessionState): PushError? {
    s.ended?.let { return it.failure ?: if (it.reason == PushEndReason.START_FAILED) PushError.UNREACHABLE else null }
    if (s.engine.phase == PushPhase.FAILED && s.items.size <= 1) return s.engine.failure?.error
    return null
}

private fun onFailureAction(a: PushCopy.Action, vm: ShareViewModel, onDiagnostics: () -> Unit, onInstall: () -> Unit) {
    when (a) {
        PushCopy.Action.RETRY -> vm.retry()
        PushCopy.Action.TRANSCODE -> vm.offerTranscode()
        PushCopy.Action.TRY_ORIGINAL -> vm.tryOriginal()
        PushCopy.Action.OTHER_DEVICE, PushCopy.Action.RESCAN, PushCopy.Action.CANCEL -> vm.changeDevice()
        PushCopy.Action.DIAGNOSTICS -> onDiagnostics()
        PushCopy.Action.INSTALL_APP -> onInstall()
        PushCopy.Action.USE_MIRROR -> vm.useMirror()
        PushCopy.Action.EDIT_URL -> vm.editUrl()
        PushCopy.Action.CHANGE_FILE, PushCopy.Action.LOCAL_FILE, PushCopy.Action.RESELECT -> vm.openRemote()
    }
}

@Composable
private fun Step(done: Boolean, active: Boolean, text: String, elder: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { if (active) liveRegion = LiveRegionMode.Polite }) {
        when {
            done -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = WscTheme.extended.success)
            active -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Box(Modifier.size(20.dp).padding(2.dp)) { Box(Modifier.fillMaxSize().background(c.outlineVariant, RoundedCornerShape(10.dp))) }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = if (elder) LocalElderTypography.current.body else MaterialTheme.typography.bodyLarge, color = if (done || active) c.onSurface else c.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ S03 已开始

@Composable
private fun StartedContent(u: ShareUi, vm: ShareViewModel, elder: Boolean) {
    val session by vm.session.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val s = session?.takeIf { it.sessionId == u.sessionId }
    val c = MaterialTheme.colorScheme
    val tv = u.target?.name ?: stringResource(R.string.share_tv)
    val photo = s?.current?.kind == MediaKind.IMAGE
    val title = ShareCopy.started(tv, photo).asString()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(WscTheme.extended.successContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, null, tint = WscTheme.extended.onSuccessContainer)
        }
        Text(title, Modifier.focusRequester(focus).focusable().semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = titleStyle(elder))
        s?.current?.let { cur ->
            Text(if (s.items.size > 1) stringResource(R.string.share_item_of, cur.index + 1, s.items.size, cur.title) else cur.title, style = body(elder), color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val copies = u.content.items.withIndex().filter { it.value.mode == ReadMode.COPY }
        if (copies.size > 1 && progress.isNotEmpty()) {
            val pending = copies.count { progress.getOrNull(it.index)?.phase.let { p -> p == ItemPhase.WAITING || p == ItemPhase.COPYING } }
            val failed = copies.filter { progress.getOrNull(it.index)?.phase == ItemPhase.FAILED }
            if (pending > 0) {
                val total = copies.sumOf { it.value.sizeBytes ?: 0L }
                val done = copies.sumOf { progress.getOrNull(it.index)?.let { p -> if (p.phase == ItemPhase.READY) it.value.sizeBytes ?: p.copiedBytes else p.copiedBytes } ?: 0L }
                val pct = ShareCopy.percent(done, total.takeIf { it > 0 })
                // 其余项：转圈 + 线性进度（UX P2-16）
                Column(Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(ShareCopy.restPreparing(pending, pct).asString(), style = body(elder))
                    }
                    if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = WscTheme.extended.success)
                    Spacer(Modifier.width(8.dp))
                    Text(ShareCopy.allReady(u.content.items.size - failed.size).asString(), style = body(elder))
                }
            }
            failed.forEach { InfoBar(ShareCopy.itemSkipped(it.index, progress[it.index].failure == CopyFailure.GONE).asString(), null, elder, Icons.Rounded.InsertDriveFile) }
        }
        Text(stringResource(R.string.share_started_note), style = small(elder), color = c.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ 对话框（P02 / S07 / 修改网址）

@Composable
private fun ShareDialogs(vm: ShareViewModel, elder: Boolean) {
    val d by vm.dialog.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val mirrorSt by vm.mirrorState.collectAsStateWithLifecycle()
    val u by vm.ui.collectAsStateWithLifecycle()
    val transcodeTail = if (session?.transcode?.phase == TranscodeUi.Phase.RUNNING || session?.transcode?.phase == TranscodeUi.Phase.PAUSED_THERMAL) stringResource(R.string.share_transcode_tail) else ""
    val newSummary = if (u.content.url != null) stringResource(R.string.share_url_word) else summaryText(u.content.kinds)
    val currentTv = session?.target?.name ?: stringResource(R.string.share_current_tv)
    when (val dialog = d) {
        // 长辈模式不会走到首次确认（只列确认过的电视）
        is ShareDialog.Confirm -> PushConfirmDialog(dialog.row.target!!, onCancel = vm::dismissDialog, onConfirm = vm::confirmDialog)
        is ShareDialog.Replace -> {
            val s = session
            PushMutexDialog(
                ShareCopy.replaceTitle(dialog.row.name).asString(),
                ShareCopy.replaceBody(s?.current?.title, s?.engine?.index ?: 0, s?.items?.size ?: 1, newSummary).asString().let { body ->
                    if (transcodeTail.isEmpty()) body else stringResource(R.string.share_body_with_tail, body, transcodeTail)
                },
                stringResource(R.string.share_replace_confirm), onCancel = vm::dismissDialog, onConfirm = vm::confirmDialog,
            )
        }
        is ShareDialog.Switch -> PushMutexDialog(
            ShareCopy.switchTitle(currentTv, dialog.row.name).asString(),
            stringResource(R.string.share_switch_body, currentTv, transcodeTail).trimEnd(),
            stringResource(R.string.share_switch_confirm, dialog.row.name), onCancel = vm::dismissDialog, onConfirm = vm::confirmDialog,
        )
        is ShareDialog.StopMirror -> PushMutexDialog(
            ShareCopy.stopMirrorTitle(dialog.row.name, dialog.connecting).asString(),
            ShareCopy.stopMirrorBody(mirrorTargetName(mirrorSt)).asString(),
            ShareCopy.stopMirrorConfirm(dialog.connecting).asString(), onCancel = vm::dismissDialog, onConfirm = vm::confirmDialog,
        )
        ShareDialog.EditUrl -> EditUrlDialog(u.content.url.orEmpty(), elder, onDismiss = vm::dismissDialog, onConfirm = vm::setUrl)
        null -> Unit
    }
}

/** 修改网址（D3 条件：输入框标签、网址键盘、自动聚焦并弹出键盘、完成键提交、确认按钮写具体动作，UX P2-18）。 */
@Composable
private fun EditUrlDialog(initial: String, elder: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = PushCopy.isPushableUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_edit_url_title), style = if (elder) LocalElderTypography.current.title else MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(stringResource(R.string.share_url_label)) },
                    singleLine = false,
                    textStyle = (if (elder) LocalElderTypography.current.body else MaterialTheme.typography.bodyMedium).copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(urlKindHintText(text), style = small(elder), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = ok) { Text(stringResource(R.string.share_url_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.share_cancel)) } },
    )
}

// ------------------------------------------------------------------ 文案辅助

/** 内容摘要“3 个视频 · 2 张照片”（各段按系统语言解析，用“ · ”连接）。 */
@Composable
private fun summaryText(kinds: List<MediaKind>): String = ShareCopy.summary(kinds).map { it.asString() }.joinToString(" · ")

// 以下几处复用 F13 推送文案（[PushCopy]），集中在这里便于对齐它的接口。

@Composable
private fun pushFailureTexts(error: PushError, tv: String, s: PushSessionState): Pair<String, String> =
    PushCopy.failure(error, tv, s.current, s.ownApp).let { it.title.asString() to it.detail.asString() }

@Composable
private fun pushFailureActions(error: PushError, tv: String, s: PushSessionState): List<PushCopy.Action> =
    PushCopy.failure(error, tv, s.current, s.ownApp).let { listOfNotNull(it.primary, it.secondary, it.tertiary) }

@Composable
private fun actionLabel(a: PushCopy.Action): String = stringResource(a.labelRes)

@Composable
private fun urlKindHintText(url: String): String = PushCopy.urlKindHint(url).asString()
