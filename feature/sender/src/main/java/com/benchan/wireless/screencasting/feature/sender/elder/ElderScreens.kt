package com.benchan.wireless.screencasting.feature.sender.elder

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.ui.theme.ElderDimens
import com.benchan.wireless.screencasting.core.ui.theme.LocalElderTypography
import com.benchan.wireless.screencasting.core.ui.theme.MonoSmall
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.feature.sender.ui.push.rememberThumbnail
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import kotlinx.coroutines.delay

/** 系统语言是否中文（预演文字跟随系统语言，EMU-F3）。 */
internal fun systemZh(): Boolean = java.util.Locale.getDefault().language == "zh"

// ------------------------------------------------------------------ E01 长辈首页

/** 正在投屏 / 推送时回到首页：顶部大卡片（交互稿 E01 变体）。 */
data class ElderActive(val title: String, val openLabel: String, val onOpen: () -> Unit, val onStop: () -> Unit)

@Composable
internal fun ElderHomeScreen(
    targets: List<ElderTargetUi>,
    active: ElderActive?,
    notice: Pair<String, () -> Unit>?,
    busy: Boolean,
    onCast: (ElderHomeTarget) -> Unit,
    onPhotos: () -> Unit,
    onPhotosFor: (ElderHomeTarget) -> Unit,
    onPickOne: () -> Unit,
    onOthers: () -> Unit,
    onSettings: () -> Unit,
) {
    val t = LocalElderTypography.current
    // “局域投屏”是顶栏小字，不是标题；主按钮放在中上部（UX P1-16）
    ElderPage(title = null, topBar = "局域投屏") {
        notice?.let { (text, dismiss) ->
            ElderCard(text) { ElderButton("知道了", dismiss, kind = ElderButtonKind.OUTLINED) }
        }
        active?.let { a ->
            ElderCard(a.title, null, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) {
                ElderButton(a.openLabel, a.onOpen, kind = ElderButtonKind.OUTLINED)
                ElderButton("停止", a.onStop, kind = ElderButtonKind.STOP, minHeight = ElderDimens.StopButton, icon = Icons.Rounded.StopCircle)
            }
        }
        fun status(ui: ElderTargetUi) = when (ui.found) {
            null -> ElderCopy.statusFinding(ui.target.label)
            true -> ElderCopy.statusFound(ui.target.label, ui.target.pinned)
            false -> ElderCopy.statusMissing(ui.target.label)
        }
        if (targets.isEmpty()) {
            // 连一次成功记录都没有：主按钮改为“选一台电视”→ E04
            ElderButton(ElderCopy.PICK_ONE, onPickOne, minHeight = ElderDimens.MainButton, icon = Icons.Rounded.Tv)
        } else {
            val mirrorTargets = targets.filter { it.canMirror }
            val pushOnly = targets.filter { !it.canMirror }
            // 只有一台时状态单独一行；两台时状态并进各自按钮的副文字（UX P1-16）
            if (targets.size == 1) ElderStatusLine(targets[0].found, status(targets[0]))
            val pair = targets.size > 1
            mirrorTargets.forEach { ui ->
                ElderButton(
                    ElderCopy.castTo(ui.target.label), { onCast(ui.target) },
                    sub = if (pair) status(ui) else ElderCopy.CAST_SUB,
                    minHeight = if (pair) ElderDimens.MainButtonPair else ElderDimens.MainButton,
                    spoken = ElderCopy.mainButtonSpoken(ui.target.label, ui.found),
                    icon = Icons.Rounded.ScreenShare, enabled = !busy,
                )
            }
            if (mirrorTargets.isEmpty()) {
                // 只能推送的电视：每台一个等大主按钮“放照片和视频到 X”（UX P1-15）
                pushOnly.forEach { ui ->
                    ElderButton(
                        ElderCopy.photosTo(ui.target.label), { onPhotosFor(ui.target) },
                        sub = if (pair) status(ui) else null,
                        minHeight = if (pair) ElderDimens.MainButtonPair else ElderDimens.MainButton, icon = Icons.Rounded.PhotoLibrary, enabled = !busy,
                    )
                }
                Text(ElderCopy.PUSH_ONLY_NOTE, style = t.body, color = MaterialTheme.colorScheme.onSurface)
            } else {
                ElderButton(ElderCopy.PHOTOS, onPhotos, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.SecondaryButton, icon = Icons.Rounded.PhotoLibrary, enabled = !busy)
            }
        }
        // 用户拍板：保留“选其他设备”（PRD 由产品回写）
        ElderButton(ElderCopy.OTHER_DEVICES, onOthers, kind = ElderButtonKind.OUTLINED, enabled = !busy)
        ElderButton(ElderCopy.SETTINGS, onSettings, kind = ElderButtonKind.TEXT)
    }
}

// ------------------------------------------------------------------ E04 选其他设备 / 放到哪台

@Composable
internal fun ElderOthersScreen(
    others: List<ElderOtherDevice>,
    onBack: () -> Unit,
    onCast: (ElderOtherDevice) -> Unit,
    onPhotos: (ElderOtherDevice) -> Unit,
    onHelp: () -> Unit,
) {
    val t = LocalElderTypography.current
    var chosen by remember { mutableStateOf<ElderOtherDevice?>(null) }
    val pick = chosen
    if (pick != null) {
        ElderPage(title = pick.device.name, back = "返回", onBack = { chosen = null }) {
            ElderButton(ElderCopy.castTo(pick.device.name), { onCast(pick) }, minHeight = ElderDimens.MainButtonPair, icon = Icons.Rounded.ScreenShare)
            ElderButton("把照片和视频放到 ${pick.device.name}", { onPhotos(pick) }, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.SecondaryButton, icon = Icons.Rounded.PhotoLibrary)
        }
        return
    }
    ElderPage(title = ElderCopy.PICK_ONE, back = "返回", onBack = onBack) {
        others.forEach { o ->
            // 行：左对齐、带图标、名称用按钮字号（UX P2-24）
            ElderButton(
                o.device.name, {
                    when {
                        o.canMirror && o.canPush -> chosen = o
                        o.canMirror -> onCast(o)
                        else -> onPhotos(o)
                    }
                },
                kind = ElderButtonKind.OUTLINED, minHeight = ElderDimens.Row, alignStart = true,
                icon = if (o.canMirror) Icons.Rounded.ScreenShare else Icons.Rounded.PlayCircleOutline,
                sub = if (o.canMirror && o.canPush) "可以投屏，也能放照片" else if (o.canMirror) "可以投屏" else "只能放照片和视频",
            )
        }
        Text("要连新的电视，请让家人帮忙设置", style = t.body, color = MaterialTheme.colorScheme.onSurface)
        if (others.isEmpty()) ElderButton("请家人帮忙", onHelp, kind = ElderButtonKind.SECONDARY)
    }
}

@Composable
internal fun ElderChooseTvScreen(targets: List<ElderHomeTarget>, onPick: (ElderHomeTarget) -> Unit, onBack: () -> Unit) {
    ElderPage(title = "放到哪台电视？", back = "返回", onBack = onBack) {
        targets.forEach { ElderButton(it.label, { onPick(it) }, minHeight = ElderDimens.MainButtonPair, icon = Icons.Rounded.Tv) }
    }
}

// ------------------------------------------------------------------ E05 录屏提示预演

@Composable
internal fun ElderCaptureIntro(onOk: () -> Unit, onBack: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val sdk = Build.VERSION.SDK_INT
    val zh = systemZh()
    val (title, body) = ElderCopy.capturePrompt(sdk, zh)
    val scope = ElderCopy.captureScopeHint(sdk, zh)
    val button = ElderCopy.captureButton(sdk, zh)
    ElderPage(title = title, back = "返回", onBack = onBack, footer = { ElderButton("好的", onOk) }) {
        Text(body, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.title, color = c.onSurface)
        scope?.let { Text(it, style = t.body, color = c.onSurface) }
        // 示意图：与系统提示同样的文字与位置，按钮被圈出；整体是一张图片，不可聚焦到假按钮
        Column(
            Modifier.fillMaxWidth().background(c.surfaceContainerHighest, RoundedCornerShape(24.dp)).padding(20.dp)
                .clearAndSetSemantics { contentDescription = "示意图：系统提示，右下角是${button}按钮" },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (zh) "要开始使用“局域投屏”录制或投放内容吗？" else "Start recording or casting with 局域投屏?", style = t.body, color = c.onSurface)
            Text(if (zh) "“局域投屏”将可获取您屏幕上显示或设备播放的所有信息…" else "局域投屏 will have access to all of the information that is visible on your screen…", style = t.bodySmall, color = c.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(if (zh) "取消" else "Cancel", Modifier.padding(16.dp), style = t.bodySmall, color = c.onSurfaceVariant)
                Box(Modifier.border(3.dp, c.primary, RoundedCornerShape(20.dp)).padding(4.dp)) {
                    Text(button, Modifier.background(c.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 10.dp), style = t.label, color = c.onSurface)
                }
            }
            Text("点这里 ↑", Modifier.align(Alignment.End), style = t.bodySmall, color = c.onSurface)
        }
    }
}

// ------------------------------------------------------------------ E06 连接中 / 电视确认 / 4 个数字

@Composable
internal fun ElderWaitingScreen(title: String, body: String?, onCancel: () -> Unit) {
    val t = LocalElderTypography.current
    ElderPage(title = title, back = "取消", onBack = onCancel, footer = { ElderButton("取消", onCancel, kind = ElderButtonKind.OUTLINED) }) {
        Spacer(Modifier.size(16.dp))
        CircularProgressIndicator(Modifier.size(56.dp).align(Alignment.CenterHorizontally), strokeWidth = 5.dp)
        body?.let { Text(it, Modifier.fillMaxWidth(), style = t.body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
internal fun ElderPinScreen(s: SenderState.AwaitingPin, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    var pin by rememberSaveable { mutableStateOf("") }
    // 输错：格子清空，错误卡常驻直到重新输入
    LaunchedEffect(s.error) { if (s.error) pin = "" }
    val compact = LocalDensity.current.fontScale >= 1.5f
    // 键盘固定在页脚，标题和说明滚动（UX P1-6）
    ElderPage(
        title = ElderCopy.PIN_TITLE, back = "取消", onBack = onCancel,
        footer = {
            ElderKeypad(
                onDigit = { d ->
                    if (pin.length < 4 && !s.verifying) {
                        pin += d
                        // 输满 4 位自动提交（少一次“确定”）
                        if (pin.length == 4) onSubmit(pin)
                    }
                },
                onDelete = { pin = pin.dropLast(1) },
            )
        },
    ) {
        Text(ElderCopy.PIN_BODY, style = t.body, color = c.onSurface)
        if (s.error && pin.isEmpty()) {
            Text(
                ElderCopy.PIN_WRONG,
                Modifier.fillMaxWidth().background(c.errorContainer, RoundedCornerShape(16.dp)).padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                style = t.body, color = c.onErrorContainer,
            )
        }
        // 不读出数字本身，只读“已输入 n 个”（UX P1-5）
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "已输入 ${pin.length} 个，共 4 个"; liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            repeat(4) { i ->
                Box(
                    Modifier.size(if (compact) 56.dp else 64.dp, if (compact) 68.dp else 76.dp).background(c.surfaceContainerLowest, RoundedCornerShape(12.dp))
                        .border(if (i == pin.length) 3.dp else 1.dp, if (i == pin.length) c.primary else c.outlineVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(pin.getOrNull(i)?.toString() ?: "", style = t.digitCell, color = c.onSurface) }
            }
        }
        if (s.verifying) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        Text("输错了可以按「删除」", style = t.bodySmall, color = c.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ E07 投屏中

@Composable
internal fun ElderCastingScreen(label: String, state: SenderState, onStop: () -> Unit, onHome: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    ElderPage(
        title = ElderCopy.castingTo(label), back = "首页", onBack = onHome,
        footer = { ElderButton(ElderCopy.STOP, onStop, kind = ElderButtonKind.STOP, minHeight = ElderDimens.StopButton, icon = Icons.Rounded.StopCircle) },
    ) {
        when (state) {
            is SenderState.Reconnecting -> ElderCard(ElderCopy.RECONNECTING, ElderCopy.RECONNECTING_SUB, WscTheme.extended.warningContainer, WscTheme.extended.onWarningContainer) {
                CircularProgressIndicator(Modifier.size(28.dp), color = WscTheme.extended.onWarningContainer, strokeWidth = 3.dp)
            }
            is SenderState.Casting -> {
                val text = ElderCopy.quality(state.stats.grade)
                val good = text == "画面正常"
                Column(Modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Box(Modifier.size(14.dp).background(if (good) WscTheme.extended.success else WscTheme.extended.warning, RoundedCornerShape(7.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = t.title, color = c.onSurface)
                    }
                    Text(ElderCopy.elapsed(now - state.startedAt), Modifier.clearAndSetSemantics { }, style = t.body, color = c.onSurface)
                }
            }
            else -> Unit
        }
        Text(ElderCopy.CASTING_HINT, style = t.body, color = c.onSurface)
    }
}

// ------------------------------------------------------------------ E08 连不上 / E09 已经停止

@Composable
internal fun ElderFailureScreen(outcome: ElderOutcome, label: String, onRetry: () -> Unit, onHelp: () -> Unit, onWifi: () -> Unit, onBack: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val (title, body) = ElderCopy.failure(outcome, label, Build.VERSION.SDK_INT, systemZh())
    ElderPage(
        title = title, back = "返回", onBack = onBack,
        footer = {
            when (outcome) {
                ElderOutcome.NO_WIFI -> {
                    ElderButton("打开 Wi‑Fi 设置", onWifi)
                    ElderButton("再试一次", onRetry, kind = ElderButtonKind.SECONDARY)
                }
                ElderOutcome.CAPTURE_DENIED, ElderOutcome.TV_REJECTED, ElderOutcome.TV_NO_ANSWER -> ElderButton("再试一次", onRetry)
                ElderOutcome.CANNOT_PUSH, ElderOutcome.NO_TARGET, ElderOutcome.PIN_LOCKED -> {
                    ElderButton("请家人帮忙", onHelp)
                    ElderButton("回首页", onBack, kind = ElderButtonKind.SECONDARY)
                }
                ElderOutcome.DISCONNECTED -> {
                    ElderButton("重新投屏", onRetry)
                    ElderButton("请家人帮忙", onHelp, kind = ElderButtonKind.SECONDARY)
                }
                else -> {
                    ElderButton("再试一次", onRetry)
                    ElderButton("请家人帮忙", onHelp, kind = ElderButtonKind.SECONDARY)
                }
            }
        },
    ) {
        Text(body, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = t.body, color = c.onSurface)
        if (outcome == ElderOutcome.NOT_FOUND) {
            // 电视待机页缩略示意（固定深色，和真电视一致）；只能推送的电视不显示（UX P1-10）
            Column(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF111418), RoundedCornerShape(16.dp)).padding(16.dp)
                    .clearAndSetSemantics { contentDescription = "电视上应该显示：局域投屏，等待投屏" },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("局域投屏", style = t.title, color = Color(0xFFE6E8EE))
                Text("等待投屏 · $label", style = t.bodySmall, color = Color(0xFFC2C7CF))
            }
            Text("电视上应该是这个画面", style = t.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ElderEndedScreen(outcome: ElderOutcome, photos: Boolean, onAgain: () -> Unit, onHome: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val (title, body) = ElderCopy.ended(outcome, photos)
    ElderPage(
        title = title, back = "首页", onBack = onHome,
        footer = {
            ElderButton(if (photos) "再放一次" else if (outcome == ElderOutcome.LOCKED) "重新投屏" else "再投一次", onAgain)
            ElderButton("回首页", onHome, kind = ElderButtonKind.SECONDARY)
        },
    ) {
        // 72 dp 中性图标块 + 居中（UX P2-22）
        Box(Modifier.size(72.dp).align(Alignment.CenterHorizontally).background(c.surfaceContainerHigh, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(40.dp), tint = c.onSurfaceVariant)
        }
        Text(body, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, style = t.body, textAlign = TextAlign.Center, color = c.onSurface)
    }
}

// ------------------------------------------------------------------ E10 放照片和视频（遥控页大字版）

internal class ElderPushActions(
    val prev: () -> Unit,
    val next: () -> Unit,
    val toggle: () -> Unit,
    val stop: () -> Unit,
    val home: () -> Unit,
    val retry: () -> Unit,
    val help: () -> Unit,
    val acceptTranscode: () -> Unit,
    val declineTranscode: () -> Unit,
)

@Composable
internal fun ElderPushScreen(s: PushSessionState, a: ElderPushActions) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    val cur = s.current
    val e = s.engine
    val transcode = s.transcode
    var offerHidden by remember(transcode?.index) { mutableStateOf(false) }
    ElderPage(
        title = "正在放到 ${s.target.name}", back = "首页", onBack = a.home,
        footer = { ElderButton("停止", a.stop, kind = ElderButtonKind.STOP, minHeight = ElderDimens.StopButton, icon = Icons.Rounded.StopCircle) },
    ) {
        when {
            transcode != null && (transcode.phase == TranscodeUi.Phase.RUNNING || transcode.phase == TranscodeUi.Phase.PAUSED_THERMAL) -> {
                ElderCard(ElderCopy.transcodeRunning(transcode.progress, transcode.etaMs?.let { "大约还要 " + (PushCopy.minutes(it)?.removePrefix("约 ") ?: "") })) {
                    ElderButton("不放这个了", a.declineTranscode, kind = ElderButtonKind.OUTLINED)
                }
            }
            transcode != null && transcode.phase == TranscodeUi.Phase.OFFER && offerHidden -> {
                // 对话框被关掉后保留提议卡片，不等于选了“不放这个了”（UX P1-12）
                ElderCard(ElderCopy.transcodeOffer(PushCopy.minutes(transcode.estimatedDurationMs)?.let { "大约" + it.removePrefix("约 ") })) {
                    ElderButton("转换后播放", a.acceptTranscode)
                    ElderButton("不放这个了", a.declineTranscode, kind = ElderButtonKind.OUTLINED)
                }
            }
            e.phase == PushPhase.FAILED && s.items.size <= 1 -> {
                val f = PushCopy.failure(e.failure?.error ?: com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError.UNREACHABLE, s.target.name, cur, s.ownApp)
                ElderCard(f.title) {
                    ElderButton("再试一次", a.retry)
                    ElderButton("请家人帮忙", a.help, kind = ElderButtonKind.SECONDARY)
                }
            }
            e.phase == PushPhase.PREPARING || e.phase == PushPhase.LOADING || e.phase == PushPhase.IDLE -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("电视正在加载…", style = t.title, color = c.onSurface)
                }
                if (s.ownApp || e.loadingSlow) Text(ElderCopy.TV_CONFIRM, style = t.body, color = c.onSurface)
            }
        }
        if (cur != null) {
            val word = if (cur.kind == MediaKind.IMAGE) "张" else "个"
            val counter = if (cur.kind == MediaKind.IMAGE) ElderCopy.photoCounter(cur.index, s.items.size) else ElderCopy.itemCounter(cur.index, s.items.size)
            if (cur.kind == MediaKind.IMAGE) {
                val bmp = rememberThumbnail(cur.uri, 720, 720)
                // 大图：读作“照片”，提供“上一张 / 下一张”自定义操作；序号由下方文字播报（UX P2-20）
                Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black, RoundedCornerShape(16.dp))
                        .clearAndSetSemantics {
                            contentDescription = "照片"
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("上一张") { a.prev(); true }.takeIf { cur.index > 0 },
                                CustomAccessibilityAction("下一张") { a.next(); true }.takeIf { cur.index < s.items.size - 1 },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit) } }
                Text(counter, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.title, color = c.onSurface)
            } else {
                Icon(if (cur.kind == MediaKind.AUDIO) Icons.Rounded.MusicNote else Icons.Rounded.PlayCircleOutline, null, Modifier.size(56.dp), tint = c.onSurfaceVariant)
                Text(if (s.items.size > 1) counter else cur.title, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.title, color = c.onSurface)
                if (e.phase == PushPhase.PLAYING || e.phase == PushPhase.PAUSED) {
                    ElderButton(if (e.phase == PushPhase.PLAYING) "暂停" else "继续", a.toggle, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.StopButton)
                }
            }
            if (s.items.size > 1) {
                // 首张 / 末张隐藏对应按钮，另一个占满整宽（不用让长辈分辨灰色，UX P1-3）；字号大时上下排列（UX P2-21）
                val hasPrev = cur.index > 0
                val hasNext = cur.index < s.items.size - 1
                val stacked = LocalDensity.current.fontScale >= 1.5f || !(hasPrev && hasNext)
                if (stacked) {
                    if (hasPrev) ElderButton("上一$word", a.prev, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.StopButton)
                    if (hasNext) ElderButton("下一$word", a.next, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.StopButton)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(ElderDimens.Gap)) {
                        Box(Modifier.weight(1f)) { ElderButton("上一$word", a.prev, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.StopButton) }
                        Box(Modifier.weight(1f)) { ElderButton("下一$word", a.next, kind = ElderButtonKind.SECONDARY, minHeight = ElderDimens.StopButton) }
                    }
                }
            }
        }
    }
    // 转换提议 · 长辈版（交互稿 E10）：主按钮在上；点对话框外面只是收起，保留提议卡片（UX P1-12）
    if (transcode != null && transcode.phase == TranscodeUi.Phase.OFFER && !offerHidden) {
        AlertDialog(
            onDismissRequest = { offerHidden = true },
            text = { Text(ElderCopy.transcodeOffer(PushCopy.minutes(transcode.estimatedDurationMs)?.let { "大约" + it.removePrefix("约 ") }), style = t.body) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElderButton("转换后播放", a.acceptTranscode)
                    ElderButton("不放这个了", a.declineTranscode, kind = ElderButtonKind.OUTLINED)
                }
            },
        )
    }
}

// ------------------------------------------------------------------ E11 长辈设置

@Composable
internal fun ElderSettingsScreen(versionName: String, onChangeTv: () -> Unit, onExit: () -> Unit, onBack: () -> Unit) {
    val t = LocalElderTypography.current
    var confirm by remember { mutableStateOf<String?>(null) }
    ElderPage(title = "设置", back = "返回", onBack = onBack) {
        // 换电视同样“按住 3 秒 + 确认”（PRD 8.1.4 G，PM P2-21 ③）
        HoldToConfirmButton("换电视 / 改叫法", "按住 3 秒，给家人用", "换电视") { confirm = "tv" }
        HoldToConfirmButton("退出长辈模式", "按住 3 秒", "退出长辈模式") { confirm = "exit" }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("关于", style = t.label, color = MaterialTheme.colorScheme.onSurface)
            Text("版本 $versionName · 不连接互联网", style = t.body, color = MaterialTheme.colorScheme.onSurface)
        }
        Text("退出和换电视需要按住 3 秒，防止不小心按到。", style = t.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirm?.let { which ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val exit = which == "exit"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (exit) "退出长辈模式？" else "换电视或改叫法？", Modifier.semantics { heading() }, style = t.title) },
            text = { Text(if (exit) "退出后会回到普通界面。确定退出吗？" else "会打开给家人用的设置步骤。", style = t.body) },
            // 两个按钮纵向排列，安全选项在上、最醒目、默认焦点
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElderButton(if (exit) "不退出" else "不换", { confirm = null }, Modifier.focusRequester(focus))
                    ElderButton(if (exit) "退出" else "去设置", { confirm = null; if (exit) onExit() else onChangeTv() }, kind = ElderButtonKind.OUTLINED)
                }
            },
        )
    }
}

// ------------------------------------------------------------------ E12 请家人帮忙

@Composable
internal fun ElderHelpScreen(facts: ElderHelpText.Facts, onSend: () -> Unit, onBack: () -> Unit) {
    val t = LocalElderTypography.current
    val c = MaterialTheme.colorScheme
    ElderPage(
        title = "请家人帮忙", back = "返回", onBack = onBack,
        footer = { ElderButton("发给家人", onSend, spoken = "发给家人，会打开分享，由你选择发给谁") },
    ) {
        Text("把这页发给家人，他们就知道哪里出了问题。", style = t.body, color = c.onSurface)
        ElderCard(facts.reasonTitle, facts.reasonDetail.replace('\n', ' '))
        // 技术摘要刻意用普通字号（读者是家人），仍随系统字体放大
        Column(Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(16.dp)).padding(16.dp).semantics(mergeDescendants = true) {}) {
            Text("给家人看的技术信息", style = MaterialTheme.typography.titleSmall, color = c.onSurface)
            ElderHelpText.summary(facts).forEach { Text(it, style = MonoSmall.copy(fontFamily = FontFamily.Monospace), color = c.onSurface) }
        }
    }
}
