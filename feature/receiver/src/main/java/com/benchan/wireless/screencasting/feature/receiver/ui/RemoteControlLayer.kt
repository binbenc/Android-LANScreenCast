package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Pinch
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.unit.sp
import com.benchan.wireless.screencasting.feature.receiver.session.NoticeIcon
import com.benchan.wireless.screencasting.feature.receiver.session.PinchStart
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.ui.theme.ScrimAlpha
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.core.ui.theme.onScrim
import com.benchan.wireless.screencasting.feature.receiver.session.ControlNotice
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverControl
import com.benchan.wireless.screencasting.feature.receiver.session.pausedText
import kotlinx.coroutines.delay
import com.benchan.wireless.screencasting.feature.receiver.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import com.benchan.wireless.screencasting.feature.receiver.session.asString

/**
 * 交互稿 R07：控制中的触摸层。单指：按下 / 移动 / 抬起原样转发（长按、滑动由时间与轨迹自然形成）；
 * 坐标按画面内容区换算，落在黑边上的按下丢弃；触点本地画 44 dp 白环涟漪（白环 + 黑描边，任何画面上可见），抵消链路延迟。
 * 控制中单击画面 = 对方点击，**不再**切换浮层。
 */
@Composable
internal fun RemoteTouchLayer(
    control: ReceiverControl,
    actions: RemoteControlActions,
    modifier: Modifier = Modifier,
    onNotice: (ControlNotice) -> Unit = {},
    pinchHintShown: Boolean = true,
    onPinchHintShown: () -> Unit = {},
) {
    val ripples = remember { mutableStateListOf<RippleSpot>() }
    // C 期双指过程反馈（UX §5）：两指下各一个跟手白环；松手后执行时变实心并在执行时长内淡出，变化太小 150 ms 淡出
    var ringA by remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    var ringB by remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    var pinchFade by remember { androidx.compose.runtime.mutableStateOf<PinchFade?>(null) }
    val hintShown by rememberUpdatedState(pinchHintShown)
    var size by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val active by rememberUpdatedState(control.phase == ControlPhase.ACTIVE)
    Box(
        modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(actions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val w = size.width
                    val h = size.height
                    if (!active) {
                        actions.touchedWhileBlocked()
                        // 吃掉整个手势，避免落到下层切换浮层
                        do {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    // 外接鼠标：右键 = 返回、中键 = 主页（输入映射表，B 期）；左键按下 / 拖动 = 点击 / 滑动
                    val first = currentEvent
                    if (down.type == androidx.compose.ui.input.pointer.PointerType.Mouse &&
                        (first.buttons.isSecondaryPressed || first.buttons.isTertiaryPressed)
                    ) {
                        actions.key(if (first.buttons.isSecondaryPressed) RemoteKey.BACK else RemoteKey.HOME)
                        do {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    var accepted = actions.pointerDown(w, h, down.position.x, down.position.y)
                    if (accepted) ripples += RippleSpot(down.position, System.nanoTime())
                    val id = down.id
                    var pinching = false
                    var secondId: androidx.compose.ui.input.pointer.PointerId? = null
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                        val c = e.changes.firstOrNull { it.id == id }
                        if (!pinching) {
                            // C 期：第二根手指按下 → 取消单指手势，开始跟踪捏合（抬起时一次性发送 PINCH 宏）
                            val second = e.changes.firstOrNull { it.id != id && it.pressed && !it.previousPressed }
                            if (second != null && c != null && c.pressed) {
                                if (accepted) actions.pointerCancel()
                                accepted = false
                                pinching = actions.pinchStart(w, h, c.position.x, c.position.y, second.position.x, second.position.y) == PinchStart.STARTED
                                secondId = second.id
                                if (pinching) {
                                    ringA = c.position
                                    ringB = second.position
                                }
                                continue
                            }
                        } else {
                            val b = e.changes.firstOrNull { it.id == secondId }
                            if (c == null || b == null || !c.pressed || !b.pressed) {
                                val dur = actions.pinchEnd()
                                val a0 = ringA
                                val b0 = ringB
                                if (a0 != null && b0 != null) pinchFade = PinchFade(a0, b0, dur ?: 150L, executed = dur != null, key = System.nanoTime())
                                // 首次发出双指缩放后提示一次（每次安装一次，交互稿 R07P）
                                if (dur != null && !hintShown) {
                                    onNotice(ControlNotice.PinchHint)
                                    onPinchHintShown()
                                }
                                ringA = null
                                ringB = null
                                // 余下的手指抬起前不再转发
                                var stillDown = e.changes.any { it.pressed }
                                while (stillDown) {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { it.consume() }
                                    stillDown = ev.changes.any { it.pressed }
                                }
                                break
                            }
                            actions.pinchMove(w, h, c.position.x, c.position.y, b.position.x, b.position.y)
                            ringA = c.position
                            ringB = b.position
                            continue
                        }
                        if (c == null) {
                            if (accepted) actions.pointerCancel()
                            break
                        }
                        if (!c.pressed) {
                            if (accepted) actions.pointerUp(w, h, c.position.x, c.position.y)
                            break
                        }
                        if (accepted && (e.type == PointerEventType.Move || c.positionChange() != Offset.Zero)) {
                            actions.pointerMove(w, h, c.position.x, c.position.y)
                        }
                    }
                }
            }
            .pointerInput(actions) {
                // 鼠标滚轮 → W（50 ms 内多格合并，每格 8% 内容高度）；Ctrl + 滚轮 → PINCH（C 期）
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val c = e.changes.firstOrNull() ?: continue
                        val ctrl = e.keyboardModifiers.isCtrlPressed
                        val notches = com.benchan.wireless.screencasting.core.control.WheelDirection.notchesFromComposeScrollDelta(c.scrollDelta.y)
                        if (active) {
                            // Ctrl + 滚轮 = 双指缩放（C 期）；普通滚轮 = 滚动
                            if (ctrl) {
                                actions.ctrlWheel(size.width, size.height, c.position.x, c.position.y, notches)
                                // Ctrl + 滚轮：指针处显示静态环（UX §5）
                                pinchFade = PinchFade(c.position, c.position, 300L, executed = true, key = System.nanoTime(), static = true)
                            } else {
                                actions.mouseWheel(size.width, size.height, c.position.x, c.position.y, notches)
                            }
                        }
                        e.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        ripples.forEach { r -> Ripple(r) { ripples.remove(r) } }
        ringA?.let { PinchRing(it, 1f, solid = false) }
        ringB?.let { PinchRing(it, 1f, solid = false) }
        pinchFade?.let { f -> PinchFadeRings(f) { if (pinchFade?.key == f.key) pinchFade = null } }
    }
}

internal data class RippleSpot(val center: Offset, val key: Long, val static: Boolean = false)

/** 松手后的双指反馈：[executed] 时实心并在 [durMs]（执行时长）内淡出，否则空心 150 ms 淡出。 */
internal data class PinchFade(val a: Offset, val b: Offset, val durMs: Long, val executed: Boolean, val key: Long, val static: Boolean = false)

@Composable
private fun PinchFadeRings(f: PinchFade, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val anim = remember(f.key) { Animatable(1f) }
    LaunchedEffect(f.key) {
        // 系统关闭动画时：已发送 = 实心静态环 300 ms 后消失；变化 <5% = 直接消失
        if (reduceMotion || f.static) {
            if (f.executed) delay(300)
        } else {
            anim.animateTo(0f, tween(f.durMs.toInt()))
        }
        onDone()
    }
    val alpha = if (reduceMotion || f.static) 1f else anim.value
    PinchRing(f.a, alpha, solid = f.executed)
    if (f.b != f.a) PinchRing(f.b, alpha, solid = f.executed)
}

/** 44 dp 白环（白环 2 dp + 外侧 1 dp 黑描边），[solid] 时填充半透明白色。 */
@Composable
private fun PinchRing(center: Offset, alpha: Float, solid: Boolean) {
    val density = LocalDensity.current
    val r = with(density) { 22.dp.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    val outline = with(density) { 1.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        // 按住：空心（无填充）；松手已发送：实心白 40%
        if (solid) drawCircle(Color.White.copy(alpha = 0.4f * alpha), r, center)
        drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r + stroke / 2 + outline / 2, center, style = Stroke(outline))
        drawCircle(Color.White.copy(alpha = alpha), r, center, style = Stroke(stroke))
    }
}

@Composable
internal fun Ripple(spot: RippleSpot, onDone: () -> Unit) {
    val anim = remember(spot.key) { Animatable(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 系统关闭动画时显示 300 ms 的静态环（UX 建议 A7）
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    LaunchedEffect(spot.key) {
        if (reduceMotion || spot.static) delay(300) else anim.animateTo(1f, tween(450))
        onDone()
    }
    val density = LocalDensity.current
    val r = with(density) { 22.dp.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    val outline = with(density) { 1.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val alpha = 1f - anim.value
        val radius = r * (0.85f + 0.15f * anim.value)
        drawCircle(Color.White.copy(alpha = 0.22f * alpha), radius, spot.center)
        drawCircle(Color.Black.copy(alpha = 0.35f * alpha), radius + stroke / 2 + outline / 2, spot.center, style = Stroke(outline))
        drawCircle(Color.White.copy(alpha = alpha), radius, spot.center, style = Stroke(stroke))
    }
}

/**
 * 交互稿 R06：右边缘 24×80 dp 小把手（scrim 60% + onScrim 图标；控制中图标为 primary）。点按或从边缘左滑打开控制条。
 */
@Composable
internal fun ControlEdgeHandle(controlling: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val openBarDesc = stringResource(R.string.rx_ctl_open_bar)
    val density = LocalDensity.current
    val open by rememberUpdatedState(onOpen)
    var dragged by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // 视觉 24×80 dp、触控 48×80 dp；排除系统返回手势区域，手势导航下从右边缘左滑不会触发系统返回（UX / PM 走查 S1 / S2、G1）
    Box(
        modifier.size(width = 48.dp, height = 80.dp)
            .systemGestureExclusion()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -with(density) { 24.dp.toPx() }) open() },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            .clickable(onClick = onOpen)
            .semantics { contentDescription = openBarDesc },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier.size(width = 24.dp, height = 80.dp)
                .background(Color.Black.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp), tint = if (controlling) c.primary else c.onScrim)
        }
    }
}

/** 交互稿 R07：左上角角标“控制中”/“控制已暂停 · 原因”。 */
@Composable
internal fun ControlBadge(control: ReceiverControl, modifier: Modifier = Modifier, modeLabel: String? = null, tv: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val paused = control.phase == ControlPhase.PAUSED
    // TV：34 dp 高、15 sp（UX 走查 G9）；状态变化时读屏播报
    Row(
        modifier.height(if (tv) 34.dp else 30.dp).background(Color.Black.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(if (tv) 17.dp else 15.dp))
            .padding(horizontal = 14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (paused) WscTheme.extended.warning else c.primary, CircleShape))
        Text(
            when {
                paused -> stringResource(R.string.rx_ctl_badge_paused, pausedText(control.reason).asString())
                modeLabel != null -> stringResource(R.string.rx_ctl_badge_active_mode, modeLabel)
                else -> stringResource(R.string.rx_ctl_badge_active)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontSize = if (tv) 15.sp else 13.sp), color = c.onScrim,
        )
    }
}

/**
 * 交互稿 R06 / R07 / R08：底部控制条（surfaceContainerHigh 实色底，播放页固定深色方案）。
 */
@Composable
internal fun ControlBar(
    peer: String,
    control: ReceiverControl,
    actions: RemoteControlActions,
    onDisconnect: () -> Unit,
    onInteraction: () -> Unit,
    onClose: () -> Unit,
    onShowStats: () -> Unit,
    onTextInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(control.pendingDeadline, control.cooldownUntil) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    fun act(block: () -> Unit) {
        onInteraction()
        block()
    }
    Column(
        modifier.fillMaxWidth().widthIn(max = 560.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(24.dp)).padding(14.dp),
    ) {
        when {
            control.waiting -> {
                val left = if (control.phase == ControlPhase.PENDING) ((control.pendingDeadline - now + 999) / 1_000).coerceAtLeast(0) else null
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rx_ctl_waiting, peer), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
                        // 倒计时不实时播报，只在剩 10 秒时播报一次（UX 走查 G5）
                        Text(
                            if (left != null) stringResource(R.string.rx_ctl_waiting_detail_countdown, left.toInt()) else stringResource(R.string.rx_ctl_waiting_detail),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.onSurfaceVariant,
                        )
                        val tenLeft = stringResource(R.string.rx_ctl_ten_seconds_left)
                        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = if (left != null && left in 1..10) tenLeft else "" })
                    }
                    CloseButton(onClose)
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { act(actions::cancelRequest) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.rx_ctl_cancel_request)) }
            }
            control.controlling -> {
                val paused = control.phase == ControlPhase.PAUSED
                StateLine(if (paused) WscTheme.extended.warning else c.primary, if (paused) stringResource(R.string.rx_ctl_badge_paused, pausedText(control.reason).asString()) else stringResource(R.string.rx_ctl_controlling, peer), onClose)
                Spacer(Modifier.height(12.dp))
                // “输入文字”（B 期）：对方 Android 13+ 可用；对方版本不够时为不可用样式，按下说明原因（D2：已实现但版本不满足才显示灰色）
                val textOk = com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures.TEXT in control.feat
                // 第 2 行 [输入文字][退出控制][连接信息] 三个 Tonal 等宽、水平内边距 12 dp；字号 ≥130% 时流式换行（交互稿 v1.0.3 R07）。不可用一律 outline / surfaceContainer
                val tonalDisabled = ButtonDefaults.filledTonalButtonColors(disabledContainerColor = c.surfaceContainer, disabledContentColor = c.outline)
                val textDisabled = ButtonDefaults.textButtonColors(disabledContentColor = c.outline)
                val compact = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
                val cell = if (large) Modifier.heightIn(min = 48.dp) else Modifier.heightIn(min = 48.dp)
                val notSupported = stringResource(R.string.rx_ctl_peer_os_unsupported)
                val buttons: @Composable (Modifier) -> Unit = { w ->
                    FilledTonalButton(
                        onClick = { act { if (textOk) onTextInput() else actions.notifyUnsupported(com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures.TEXT, force = true) } },
                        enabled = !paused,
                        modifier = w.then(cell).semantics { if (!textOk) stateDescription = notSupported },
                        colors = if (textOk) tonalDisabled else ButtonDefaults.filledTonalButtonColors(
                            containerColor = c.surfaceContainer, contentColor = c.outline, disabledContainerColor = c.surfaceContainer, disabledContentColor = c.outline,
                        ),
                        contentPadding = compact,
                    ) { Text(stringResource(R.string.rx_ctl_input_text), maxLines = 2, textAlign = TextAlign.Center) }
                    FilledTonalButton(onClick = { act(actions::exit) }, modifier = w.then(cell), colors = tonalDisabled, contentPadding = compact) { Text(stringResource(R.string.rx_ctl_exit), maxLines = 2, textAlign = TextAlign.Center) }
                    FilledTonalButton(onClick = { act(onShowStats) }, modifier = w.then(cell), colors = tonalDisabled, contentPadding = compact) { Text(stringResource(R.string.rx_ctl_connection_info), maxLines = 2, textAlign = TextAlign.Center) }
                }
                if (large) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        buttons(Modifier)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { act { actions.key(RemoteKey.BACK) } }, enabled = !paused, colors = textDisabled, contentPadding = compact) { Text(stringResource(R.string.rx_key_back)) }
                    TextButton(onClick = { act { actions.key(RemoteKey.HOME) } }, enabled = !paused, colors = textDisabled, contentPadding = compact) { Text(stringResource(R.string.rx_key_home)) }
                    TextButton(onClick = { act { actions.key(RemoteKey.RECENTS) } }, enabled = !paused, colors = textDisabled, contentPadding = compact) { Text(stringResource(R.string.rx_key_recents)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { act(onDisconnect) }, colors = ButtonDefaults.textButtonColors(contentColor = c.error), contentPadding = compact) { Text(stringResource(R.string.rx_disconnect)) }
                }
            }
            else -> {
                val cooldownLeft = ((control.cooldownUntil - now + 999) / 1_000).coerceAtLeast(0)
                StateLine(c.outline, stringResource(R.string.rx_ctl_not_controlling, peer), onClose)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 对方未开启时按钮仍可点，点后给出原因（R08），而不是置灰让人猜；只有冷却期内不可用（不可用态 outline / surfaceContainer，UX 走查 G3）
                    Button(
                        onClick = { act(actions::request) }, enabled = cooldownLeft == 0L, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(disabledContainerColor = c.surfaceContainer, disabledContentColor = c.outline),
                    ) {
                        Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.rx_ctl_request))
                    }
                    FilledTonalButton(onClick = { act(onDisconnect) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.rx_disconnect)) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (cooldownLeft > 0) pluralStringResource(R.plurals.rx_ctl_denied_cooldown, cooldownLeft.toInt(), cooldownLeft.toInt()) else stringResource(R.string.rx_ctl_request_hint),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StateLine(dot: Color, text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        CloseButton(onClose)
    }
}

/** 控制条标题行的关闭 ×（UX 走查 S2 / G2）：读屏用户不依赖自动收起。 */
@Composable
private fun CloseButton(onClose: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClose) {
        Icon(Icons.Rounded.Close, stringResource(R.string.rx_ctl_hide_bar), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 交互稿 R08：底部提示（4 秒），标题 = 发生了什么，副标题 = 怎么办。 */
@Composable
internal fun ControlNoticeToast(notice: ControlNotice, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.widthIn(max = 480.dp).background(c.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val (icon, tint) = noticeStyle(notice)
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Column {
            Text(notice.title.asString(), style = MaterialTheme.typography.bodyMedium)
            notice.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
        }
    }
}

/** R08 提示的图标与图标色（交互稿 v1.0.3 图标表）。 */
@Composable
private fun noticeStyle(notice: ControlNotice): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val c = MaterialTheme.colorScheme
    val tint = when (notice.tone) {
        com.benchan.wireless.screencasting.feature.receiver.session.NoticeTone.ERROR -> c.error
        com.benchan.wireless.screencasting.feature.receiver.session.NoticeTone.WARNING -> WscTheme.extended.warning
        com.benchan.wireless.screencasting.feature.receiver.session.NoticeTone.NEUTRAL -> c.onSurfaceVariant
    }
    val icon = when (notice.icon) {
        NoticeIcon.CLOSE -> Icons.Rounded.Close
        NoticeIcon.HOURGLASS -> Icons.Rounded.HourglassEmpty
        NoticeIcon.A11Y -> Icons.Rounded.Accessibility
        NoticeIcon.INFO -> Icons.Rounded.Info
        NoticeIcon.REFRESH -> Icons.Rounded.Refresh
        NoticeIcon.LOCK -> Icons.Rounded.Lock
        NoticeIcon.PAUSE -> Icons.Rounded.Pause
        NoticeIcon.PHONE -> Icons.Rounded.PhoneAndroid
        NoticeIcon.HANDBACK -> Icons.AutoMirrored.Rounded.Undo
        NoticeIcon.CHECK -> Icons.Rounded.Check
        NoticeIcon.KEYBOARD -> Icons.Rounded.Keyboard
        NoticeIcon.DPAD -> Icons.Rounded.Gamepad
        NoticeIcon.CURSOR -> Icons.Rounded.NearMe
        NoticeIcon.PINCH -> Icons.Rounded.Pinch
    }
    return icon to tint
}

/** 提示出现时长（交互稿 R08：4 s）。 */
internal const val CONTROL_NOTICE_MS = 4_000L

/** 控制条无操作自动收起（交互稿 R06：5 s）；等待确认期间不收起。 */
internal const val CONTROL_BAR_IDLE_MS = 5_000L

/** 长按返回键打开控制条的阈值（产品决策：≥600 ms）。 */
internal const val BACK_LONG_PRESS_MS = 600L
