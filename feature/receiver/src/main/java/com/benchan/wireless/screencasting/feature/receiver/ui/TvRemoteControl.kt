package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.control.InKey
import com.benchan.wireless.screencasting.core.control.KeyInput
import com.benchan.wireless.screencasting.core.control.KeySource
import com.benchan.wireless.screencasting.core.control.TvControlMode
import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.ui.components.TvButton
import com.benchan.wireless.screencasting.core.ui.components.TvIconButton
import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.ui.components.tvInitialFocus
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverControl
import com.benchan.wireless.screencasting.feature.receiver.session.pausedText
import kotlinx.coroutines.delay

/** TV 控制条无操作自动收起（交互稿 R09：8 s）。 */
internal const val TV_CONTROL_BAR_IDLE_MS = 8_000L

/** 方向键模式下无可见焦点变化的提示时长（交互稿 R11：5 s，按时长近似）。 */
internal const val DPAD_FOCUS_HINT_MS = 5_000L

/**
 * Android KeyEvent → 平台无关的 [KeyInput]。
 * TV 按 **keycode** 区分（PM 决策 ④）：DPAD_* / DPAD_CENTER 一律按遥控器（跟随当前 TV 模式：光标 / 方向键），
 * 外接键盘的方向键在 TV 上也移动光标；字符、Esc、Meta、Alt+Tab、Backspace、KEYCODE_ENTER 用键盘语义。触屏接收端一律按键盘处理。
 */
internal fun KeyEvent.toKeyInput(isTv: Boolean): KeyInput {
    val remoteKey = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
    val source = if (isTv && remoteKey) KeySource.REMOTE else KeySource.KEYBOARD
    val k = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> InKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> InKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> InKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> InKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> InKey.CENTER
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> InKey.ENTER
        KeyEvent.KEYCODE_BACK -> InKey.BACK
        KeyEvent.KEYCODE_ESCAPE -> InKey.ESCAPE
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> InKey.META
        KeyEvent.KEYCODE_TAB -> InKey.TAB
        KeyEvent.KEYCODE_DEL -> InKey.BACKSPACE
        KeyEvent.KEYCODE_MENU -> InKey.MENU
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> InKey.PLAY_PAUSE
        else -> {
            val u = unicodeChar
            if (u != 0 && !Character.isISOControl(u) && Character.charCount(u) == 1) InKey.CHAR else InKey.OTHER
        }
    }
    val ch = if (k == InKey.CHAR) unicodeChar.toChar() else null
    return KeyInput(k, source, ch, alt = isAltPressed, ctrl = isCtrlPressed)
}

/**
 * 交互稿 R10：TV 本地绘制的光标（不进视频流、零延迟）：32 dp 白色箭头 + 深色描边 + 阴影；3 s 不动淡至 40%。
 * [x] / [y] 为箭头尖端的视图像素坐标。
 */
@Composable
internal fun TvCursor(x: Float, y: Float, alpha: Float) {
    val sizePx = with(LocalDensity.current) { 32.dp.toPx() }
    val stroke = with(LocalDensity.current) { 1.5.dp.toPx() }
    // 画布铺满并在绘制时直接带透明度：不能用 1 dp 画布 + Modifier.alpha —— alpha < 1 时 graphicsLayer 离屏渲染，
    // 内容被裁到 1 dp 的图层范围里，光标整个看不见（淡出到 40%、淡入动画期间都会这样，真机复测问题）
    Canvas(Modifier.fillMaxSize()) {
        // 交互稿图标 cursor：M5.5 3.5 18.5 12l-6 1.4-2.8 5.6z（24 视口），尖端对齐 (x, y)
        val s = sizePx / 24f
        val path = Path().apply {
            moveTo(5.5f * s, 3.5f * s)
            lineTo(18.5f * s, 12f * s)
            lineTo(12.5f * s, 13.4f * s)
            lineTo(9.7f * s, 19f * s)
            close()
        }
        translate(x - 5.5f * s, y - 3.5f * s) {
            // 阴影偏移按 dp（UX 走查 G5）
            translate(1.dp.toPx(), 2.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.35f), alpha = alpha) }
            drawPath(path, Color.White, alpha = alpha)
            drawPath(path, Color(0xFF1B1F24), style = Stroke(stroke, join = StrokeJoin.Round), alpha = alpha)
        }
    }
}

/**
 * 交互稿 R09 / R11：TV 底部控制条（菜单键或长按返回打开）。
 * - 默认焦点：未控制 = “请求控制”（打开时在冷却中则为“连接信息”，冷却结束**不抢焦点**）；等待 = “取消请求”；控制中 = “关闭”。
 * - 焦点组：左右到头循环，上下不会离开控制条；任意按键重置 8 s 自动收起计时；返回键 = 关闭。
 * - 当前模式 = 选中态（primaryContainer + ✓）；对方不支持的按钮为不可用态（仍可聚焦），聚焦时状态行说明原因、每次按下都提示。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvControlBar(
    peer: String,
    control: ReceiverControl,
    mode: TvControlMode,
    actions: RemoteControlActions,
    onDisconnect: () -> Unit,
    onShowStats: () -> Unit,
    onClose: () -> Unit,
    onMode: (TvControlMode) -> Unit,
    onTextInput: () -> Unit,
    onInteraction: () -> Unit,
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
    val dpadOk = ControlFeatures.DPAD in control.feat
    val textOk = ControlFeatures.TEXT in control.feat
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    var focusHint by remember { mutableStateOf<String?>(null) }
    // 打开时是否在冷却：只在首次组合时决定默认焦点，冷却结束不再抢焦点（UX 走查 G12）
    val openedInCooldown = remember { control.cooldownUntil > System.currentTimeMillis() }
    Column(
        modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 16.dp)
            .onPreviewKeyEvent {
                onInteraction()
                false
            }
            .focusProperties {
                onExit = {
                    // 左右到头循环；上下不离开控制条（UX 走查 G19）
                    cancelFocusChange()
                    when (requestedFocusDirection) {
                        FocusDirection.Right, FocusDirection.Next -> runCatching { first.requestFocus() }
                        FocusDirection.Left, FocusDirection.Previous -> runCatching { last.requestFocus() }
                        else -> Unit
                    }
                }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            control.waiting -> {
                val left = if (control.phase == ControlPhase.PENDING) ((control.pendingDeadline - now + 999) / 1_000).coerceAtLeast(0) else null
                StatusRow(
                    dot = null,
                    title = "等待 $peer 确认…" + (left?.let { " $it" } ?: ""),
                    subtitle = "请在对方手机上点「允许本次」",
                    onClose = { act(onClose) },
                    leading = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.primary) },
                )
                // 倒计时只在剩 10 秒时播报一次
                Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = if (left != null && left in 1..10) "还剩 10 秒" else "" })
                ButtonFlow {
                    TvButton("取消请求", { act(actions::cancelRequest) }, Modifier.focusRequester(first).focusRequester(last).tvInitialFocus())
                }
            }
            control.controlling -> {
                val paused = control.phase == ControlPhase.PAUSED
                StatusRow(
                    dot = if (paused) WscTheme.extended.warning else c.primary,
                    title = if (paused) "控制已暂停 · ${pausedText(control.reason)}" else "正在控制 $peer",
                    subtitle = focusHint ?: "返回键 = 对方返回",
                    onClose = { act(onClose) },
                    // 修饰符作用在关闭 × 上：控制中默认焦点“关闭”
                    modifier = Modifier.tvInitialFocus(),
                )
                ButtonFlow {
                    TvButton("光标", { act { onMode(TvControlMode.CURSOR) } }, Modifier.focusRequester(first), selected = mode == TvControlMode.CURSOR)
                    // 对方低于 Android 13：按钮仍可聚焦，聚焦时状态行说明原因、每次按下都提示（交互稿 R11 / v1.0.3）
                    TvButton(
                        if (dpadOk) "方向键" else "方向键（对方不支持）",
                        { act { if (dpadOk) onMode(TvControlMode.DPAD) else actions.notifyUnsupported(ControlFeatures.DPAD, force = true) } },
                        Modifier.onFocusChanged { focusHint = if (it.isFocused && !dpadOk) "对方系统版本不支持方向键模式" else focusHint.takeUnless { h -> h == "对方系统版本不支持方向键模式" } },
                        selected = mode == TvControlMode.DPAD && dpadOk,
                        unavailable = !dpadOk,
                    )
                    TvButton("主页", { act { actions.key(RemoteKey.HOME) } }, enabled = !paused)
                    TvButton("最近任务", { act { actions.key(RemoteKey.RECENTS) } }, enabled = !paused)
                    TvButton(
                        "输入文字",
                        { act { if (textOk) onTextInput() else actions.notifyUnsupported(ControlFeatures.TEXT, force = true) } },
                        Modifier.onFocusChanged { focusHint = if (it.isFocused && !textOk) "对方系统版本不支持文字输入" else focusHint.takeUnless { h -> h == "对方系统版本不支持文字输入" } },
                        enabled = !paused,
                        unavailable = !textOk,
                    )
                }
                ButtonFlow {
                    TvButton("退出控制", { act(actions::exit) })
                    TvButton("连接信息", { act(onShowStats) })
                    TvButton("断开投屏", { act(onDisconnect) }, Modifier.focusRequester(last))
                }
            }
            else -> {
                val cooldownLeft = ((control.cooldownUntil - now + 999) / 1_000).coerceAtLeast(0)
                StatusRow(
                    dot = c.outline,
                    title = peer,
                    subtitle = if (cooldownLeft > 0) "$cooldownLeft 秒后可以再次请求" else "未控制 · 对方确认后可用遥控器操作手机",
                    onClose = { act(onClose) },
                )
                ButtonFlow {
                    val canRequest = cooldownLeft == 0L
                    TvButton(
                        "请求控制", { act(actions::request) },
                        Modifier.focusRequester(first).then(if (!openedInCooldown) Modifier.tvInitialFocus() else Modifier),
                        enabled = canRequest, emphasis = true,
                    )
                    TvButton("连接信息", { act(onShowStats) }, if (openedInCooldown) Modifier.tvInitialFocus() else Modifier)
                    TvButton("断开投屏", { act(onDisconnect) }, Modifier.focusRequester(last))
                }
            }
        }
    }
}

/**
 * 控制条状态行：圆点 / 前置内容 + 标题与说明（占剩余宽度、可换行）+ 固定 48 dp 的关闭 ×。
 * 非 weight 的子项（圆点、×）先测量且尺寸固定，标题列只拿剩余宽度：窄屏（竖屏 / force_tv）下也不会把按钮挤成 0 宽。
 */
@Composable
private fun StatusRow(
    dot: Color?,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        dot?.let { Box(Modifier.size(10.dp).background(it, CircleShape)) }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        }
        TvIconButton(androidx.compose.material.icons.Icons.Rounded.Close, "关闭", onClose, modifier)
    }
}

/**
 * 控制条按钮行：流式换行。每个按钮都按自身内容宽度测量（最大宽度 = 控制条宽度），放不下就换到下一行，
 * 不会像 Row 那样把后面的按钮测量成 0 宽（0 宽的 tv-material 按钮绘制边框时会崩溃）。
 */
@Composable
private fun ButtonFlow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** 交互稿 R10：首次进入控制（TV）的操作提示卡，默认焦点“知道了”。 */
@Composable
internal fun TvControlHintCard(onDismiss: () -> Unit, modifier: Modifier = Modifier, mode: TvControlMode = TvControlMode.CURSOR) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // 右上角安全区、宽 ≤360 dp（UX 走查 G8），不遮挡画面中央
    Column(
        modifier.widthIn(max = 360.dp).background(c.surfaceContainerHigh, RoundedCornerShape(24.dp)).padding(horizontal = 26.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("用遥控器操作手机", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        // 记住的模式是方向键模式时显示方向键模式说明（交互稿 v1.0.3）
        (
            if (mode == TvControlMode.DPAD) {
                listOf(
                    "方向键" to "移动对方手机的焦点",
                    "确定" to "点击当前焦点",
                    "返回" to "对方手机的“返回”",
                    "菜单" to "控制条：换光标模式、退出、断开",
                )
            } else {
                listOf(
                    "方向键" to "移动光标，按住加速",
                    "确定" to "点击",
                    "长按确定" to "长按",
                    "顶 / 底边继续按" to "滚动页面",
                    "返回" to "对方手机的“返回”",
                    "菜单" to "控制条：退出、断开",
                )
            }
            ).forEach { (k, v) ->
            Row {
                Text(k, Modifier.width(132.dp), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(4.dp))
        TvButton("知道了", onDismiss, Modifier.focusRequester(focus), emphasis = true)
    }
}

/**
 * “输入文字”（手机 R07：本机键盘；TV：电视输入法 + TvButton）：整段输入后按“输入”一次送出，避免输入法组合态错乱。对方 Android 13+。
 * 草稿由调用方保存：发送失败 / 不能发送时保留，再次打开预填；不能发送时对话框保持打开并显示 [error]。
 */
@Composable
internal fun RemoteTextDialog(
    peer: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    error: String?,
    tv: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val c = MaterialTheme.colorScheme
    val max = com.benchan.wireless.screencasting.core.protocol.control.InputCodec.MAX_TEXT_CHARS
    val canSend = draft.isNotEmpty()
    val atLimit = draft.length >= max
    // 交互稿 v1.0.3 R07T：标题 heading；说明 bodyMedium / onSurfaceVariant（TV ≥16 sp）；2–5 行；计数右对齐、等宽数字，
    // 到上限变 error 色并只播报一次“已达到 1000 字上限”（计数本身不设 liveRegion）
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入到 $peer", Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "将输入到对方手机当前选中的输入框。密码、验证码请让对方自己输入。",
                    style = if (tv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { onDraftChange(it.take(max)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).then(if (tv) Modifier.tvFocusRing(8.dp) else Modifier)
                        .semantics { contentDescription = "要输入的文字" },
                    placeholder = { Text("要输入的文字") },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend(draft) }),
                    minLines = 2,
                    maxLines = 5,
                )
                Text(
                    "${draft.length} / $max",
                    Modifier.fillMaxWidth(),
                    style = (if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall).copy(fontFeatureSettings = "tnum"),
                    color = if (atLimit) c.error else c.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = if (atLimit) "已达到 1000 字上限" else "" })
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.error) }
            }
        },
        confirmButton = {
            if (tv) {
                TvButton("输入", { onSend(draft) }, enabled = canSend, emphasis = true)
            } else {
                TextButton(onClick = { onSend(draft) }, enabled = canSend, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(disabledContentColor = c.outline)) { Text("输入") }
            }
        },
        dismissButton = {
            if (tv) TvButton("取消", onDismiss) else TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
