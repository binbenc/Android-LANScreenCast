package com.benchan.wireless.screencasting.feature.receiver.ui

import com.benchan.wireless.screencasting.core.common.WscLog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.ui.semantics.contentDescription
import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyBreakdown
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.withFrameNanos
import com.benchan.wireless.screencasting.feature.receiver.session.NoticeKind
import com.benchan.wireless.screencasting.core.control.InputGeometry
import com.benchan.wireless.screencasting.core.control.KeyAction
import com.benchan.wireless.screencasting.core.control.KeyMapper
import com.benchan.wireless.screencasting.core.control.TvControlMode
import com.benchan.wireless.screencasting.core.control.TvCursorModel
import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.layout.onSizeChanged
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.ui.theme.WscAppTheme
import com.benchan.wireless.screencasting.feature.receiver.session.ControlNotice
import kotlinx.coroutines.flow.SharedFlow
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppsOutage
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.ui.components.LatencySparkline
import com.benchan.wireless.screencasting.core.ui.components.LocalIsTv
import com.benchan.wireless.screencasting.core.ui.components.LocalModalOverlayVisible
import com.benchan.wireless.screencasting.core.ui.theme.ScrimAlpha
import com.benchan.wireless.screencasting.core.ui.theme.onScrim
import com.benchan.wireless.screencasting.core.ui.components.TvButton
import com.benchan.wireless.screencasting.core.ui.components.formatMbps
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.receiver.session.MirrorSession
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import java.util.Locale
import com.benchan.wireless.screencasting.feature.receiver.R
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.feature.receiver.session.UiText
import com.benchan.wireless.screencasting.feature.receiver.session.uiText
import com.benchan.wireless.screencasting.feature.receiver.session.asString
import com.benchan.wireless.screencasting.core.ui.text.labelRes
import com.benchan.wireless.screencasting.core.ui.text.displayLabel
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import androidx.compose.foundation.layout.wrapContentSize

/**
 * 交互稿 13（镜像播放中 · 菜单键统计浮层）/ 14（重连中 · 保留最后一帧）/ 15（受保护内容提示）。
 * 播放画面用 AndroidView 承载 SurfaceViewRenderer（等比 + 黑边），Compose 层只放浮层，避免整树重组。
 */
@Composable
internal fun PlaybackScreen(
    session: MirrorSession,
    eglContext: EglBase.Context,
    attach: (VideoSink) -> Unit,
    detach: (VideoSink) -> Unit,
    onDisconnect: () -> Unit,
    control: RemoteControlActions? = null,
    controlNotices: SharedFlow<ControlNotice>? = null,
    tvMode: TvControlMode = TvControlMode.CURSOR,
    onTvMode: (TvControlMode) -> Unit = {},
    tvHintShown: Boolean = true,
    onTvHintShown: () -> Unit = {},
    pinchHintShown: Boolean = true,
    onPinchHintShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val backAgainMessage = stringResource(R.string.rx_back_again_disconnect_cast)
    val textPausedMessage = stringResource(R.string.rx_text_not_sent_paused)
    var overlay by remember { mutableStateOf(false) }
    // F18 G05：“一直显示延迟”只在本次投屏有效，结束即关
    var pinLatency by remember(session.sessionId) { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hint by remember { mutableStateOf(true) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var protectedDismissed by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    val c = MaterialTheme.colorScheme
    ImmersiveSystemBars()

    // —— 反向控制（PRD F23，交互稿 R06–R11；B 期起 TV 也有入口）——
    val ctl = session.control
    val controlling = control != null && ctl.controlling
    val controlEntry = control != null
    val features = remember(ctl.feat) { ctl.feat.toSet() }
    // 方向键模式需要对方 Android 13+，否则按光标模式工作
    val effectiveMode = if (tvMode == TvControlMode.DPAD && ControlFeatures.DPAD in features) TvControlMode.DPAD else TvControlMode.CURSOR
    val cursorOn = isTv && controlling && effectiveMode == TvControlMode.CURSOR
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val densityValue = androidx.compose.ui.platform.LocalDensity.current.density
    val cursorModel = remember(densityValue) { TvCursorModel(densityValue) }
    var cursorX by remember { mutableStateOf(0f) }
    var cursorY by remember { mutableStateOf(0f) }
    var cursorAlpha by remember { mutableStateOf(1f) }
    var cursorPressed by remember { mutableStateOf(false) }
    var cursorRipple by remember { mutableStateOf<RippleSpot?>(null) }
    var cursorAtEdge by remember { mutableStateOf(false) }
    // 进入光标模式（含从方向键模式切换回来）：光标恢复不透明，不沿用之前的淡出状态
    LaunchedEffect(cursorOn) {
        if (cursorOn) {
            cursorModel.touch(android.os.SystemClock.uptimeMillis())
            cursorAlpha = 1f
            cursorX = cursorModel.x.toFloat()
            cursorY = cursorModel.y.toFloat()
            WscLog.d(CURSOR_TAG, "cursor mode on")
        }
    }
    var textDialog by remember { mutableStateOf(false) }
    var hintDismissed by remember { mutableStateOf(false) }
    val hintCard = isTv && controlling && !tvHintShown && !hintDismissed
    // 方向键模式“看不到焦点？”：5 s 内按方向键 ≥6 次且没有按确定（UX G16 / PM G6）
    val dpadPresses = remember { ArrayDeque<Long>() }
    var textDraft by remember { mutableStateOf("") }
    var textError by remember { mutableStateOf<String?>(null) }
    var lastTextSentAt by remember { mutableLongStateOf(0L) }
    var lastTextRejectAt by remember { mutableLongStateOf(0L) }
    var swallowBackUp by remember { mutableStateOf(false) }
    LaunchedEffect(viewSize, ctl.frameWidth, ctl.frameHeight) {
        InputGeometry.videoRectInView(viewSize.width, viewSize.height, ctl.frameWidth, ctl.frameHeight)?.let {
            cursorModel.setBounds(it)
            cursorX = cursorModel.x.toFloat()
            cursorY = cursorModel.y.toFloat()
        }
    }
    var controlBar by remember { mutableStateOf(false) }
    var barInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notice by remember { mutableStateOf<ControlNotice?>(null) }
    var backDownAt by remember { mutableLongStateOf(-1L) }
    var backLongFired by remember { mutableStateOf(false) }
    var barHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val a11yManager = androidx.compose.ui.platform.LocalAccessibilityManager.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(controlNotices) {
        controlNotices?.collect { n ->
            notice = n
            if (n.kind == NoticeKind.SECURE_FIELD || n.kind == NoticeKind.NO_EDITOR) lastTextRejectAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(notice?.durationMs ?: CONTROL_NOTICE_MS)
            notice = null
        }
    }
    // 控制条 5 秒无操作自动收起；等待对方确认时保持显示（倒计时）。
    // 开着 TalkBack 等无障碍服务时按系统推荐时长（通常不自动收起），并提供关闭 ×（UX 走查 S2）
    LaunchedEffect(controlBar, barInteraction, ctl.waiting) {
        if (controlBar && !ctl.waiting) {
            val base = if (isTv) TV_CONTROL_BAR_IDLE_MS else CONTROL_BAR_IDLE_MS
            val timeout = a11yManager?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsText = true, containsControls = true)
                ?: base
            if (timeout == Long.MAX_VALUE) return@LaunchedEffect
            delay(timeout)
            controlBar = false
        }
    }
    // 控制中每帧驱动输入合并器（缓冲的移动、保活、文字与滚轮）与 TV 光标（加速、淡出、按住确定时拖动）
    LaunchedEffect(controlling, cursorOn) {
        if (controlling && control != null) {
            while (true) {
                withFrameMillis {
                    control.tick()
                    if (cursorOn) {
                        val now = android.os.SystemClock.uptimeMillis()
                        val f = cursorModel.frame(now)
                        if (f.atEdge != cursorAtEdge) {
                            cursorAtEdge = f.atEdge
                            WscLog.d(CURSOR_TAG, if (f.atEdge) "edge reached" else "edge left")
                        }
                        if (f.moved) {
                            cursorX = cursorModel.x.toFloat()
                            cursorY = cursorModel.y.toFloat()
                            if (cursorPressed) control.pointerMove(viewSize.width, viewSize.height, cursorX, cursorY)
                        }
                        // 按住在顶 / 底边：300 ms 后每 500 ms 滚动 1/3 屏（用户决策）
                        f.scroll?.let {
                            WscLog.d(CURSOR_TAG, "edge scroll (hold) ${if (it.dy > 0) "down" else "up"}")
                            control.wheel(viewSize.width, viewSize.height, cursorX, cursorY, it.dy)
                        }
                        cursorAlpha = cursorModel.alpha(now)
                    }
                }
            }
        }
    }
    // 进入控制后收起统计浮层（浮层只能从把手 / 控制条打开）
    LaunchedEffect(controlling) { if (controlling) overlay = false }

    LaunchedEffect(session.firstFrame) {
        if (session.firstFrame) {
            hint = true
            delay(if (isTv) 5_000 else 3_000)
            hint = false
        }
    }
    // TV 获得控制时：收起控制条；首次把焦点交给提示卡，之后只提示“已获得控制 · 菜单键打开控制条”（UX 走查 G11）
    LaunchedEffect(controlling) {
        if (controlling && isTv) {
            controlBar = false
            if (tvHintShown) notice = ControlNotice.Granted
        }
        // 控制结束：关闭“输入文字”对话框（草稿保留）
        if (!controlling) textDialog = false
    }
    // 发送后 3 s 内没有收到“密码框 / 没有输入框”的拒绝 → 清空草稿；被拒则保留，再次打开时预填（UX G17 / PM G2）
    LaunchedEffect(lastTextSentAt) {
        if (lastTextSentAt > 0) {
            delay(3_000)
            if (lastTextRejectAt < lastTextSentAt) textDraft = ""
        }
    }
    LaunchedEffect(overlay, lastInteraction) {
        if (overlay) {
            delay(8_000)
            overlay = false
        }
    }
    LaunchedEffect(session.protectedSuspected) { if (!session.protectedSuspected) protectedDismissed = false }
    // 浮层收起（含 8 秒自动收起）/ 屏 15“知道了”后把焦点还给根节点，遥控器按键才不会丢（B6）
    val cardShown = session.protectedSuspected && !protectedDismissed && !session.reconnecting
    // 确认弹窗等根部弹层显示期间不抢焦点，关闭后焦点回到 rootFocus（U2）
    val modalVisible = LocalModalOverlayVisible.current
    LaunchedEffect(overlay, cardShown, modalVisible, controlBar, hintCard, textDialog) {
        if (!modalVisible && !overlay && !cardShown && !controlBar && !hintCard && !textDialog) runCatching { rootFocus.requestFocus() }
    }

    // 返回键 1 次 → 提示；2 秒内第 2 次 → 断开（PRD 6.2：连按两次返回才断开）
    // 根部弹层可见时把返回键让给弹层（返回 = 拒绝），不计入“连按两次断开”（S-d）
    val modalShown = LocalModalOverlayVisible.current
    fun doubleBackToDisconnect() {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2_000) {
            onDisconnect()
        } else {
            lastBack = now
            Toast.makeText(context, backAgainMessage, Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = !modalShown && !controlling) { doubleBackToDisconnect() }
    // 控制中：返回（含手势返回）= 对方手机的“返回”；断开只能从控制条（产品决策 ①）
    BackHandler(enabled = controlling && !modalShown) { control?.key(RemoteKey.BACK) }
    // 浮层打开时，返回键先收起浮层（后注册的 BackHandler 优先）
    BackHandler(enabled = overlay && !modalShown) { overlay = false }
    BackHandler(enabled = controlBar && !modalShown) { controlBar = false }
    // 控制中打开了 15 受保护卡：返回 = 收起卡片（不发给对方）；提示卡：返回 = “知道了”（PM / UX 走查 S2）
    BackHandler(enabled = controlling && cardShown && !modalShown) { protectedDismissed = true }
    BackHandler(enabled = hintCard && !modalShown) {
        hintDismissed = true
        onTvHintShown()
    }

    Box(
        Modifier.fillMaxSize().background(c.scrim)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                // 弹层（统计浮层 / 15 卡 / 提示卡 / 控制条 / 文字对话框）可见时按键不发给对方（PM / UX 走查 S2）
                val layerOpen = controlBar || overlay || cardShown || hintCard || textDialog || modalShown
                if (controlling && layerOpen && code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_MENU &&
                    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0
                ) {
                    // 追踪：弹层开着时按键归弹层，不发给对方 / 不移动光标
                    WscLog.d(CURSOR_TAG, "key kept by layer (bar=$controlBar overlay=$overlay card=$cardShown hint=$hintCard text=$textDialog)")
                }
                if (code == KeyEvent.KEYCODE_BACK && controlEntry && (swallowBackUp || !layerOpen)) {
                    // 长按返回 ≥600 ms 打开控制条（任何状态，遥控器无菜单键时的兜底，UX S2）；长按触发后吞掉抬起，避免控制条刚打开就被关闭。
                    // 短按：控制中 = 对方“返回”；未控制 = 连按两次断开
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                backDownAt = now
                                backLongFired = false
                            } else if (!backLongFired && backDownAt >= 0 && now - backDownAt >= BACK_LONG_PRESS_MS) {
                                backLongFired = true
                                swallowBackUp = true
                                controlBar = true
                                barInteraction = System.currentTimeMillis()
                            }
                        }
                        KeyEventType.KeyUp -> {
                            if (swallowBackUp) {
                                swallowBackUp = false
                            } else if (!backLongFired && backDownAt >= 0) {
                                if (controlling) control?.key(RemoteKey.BACK) else doubleBackToDisconnect()
                            }
                            backDownAt = -1
                        }
                    }
                    true
                } else if (controlling && control != null && !layerOpen && code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_MENU) {
                    handleControlKey(
                        e.nativeKeyEvent, isTv, effectiveMode, features, control, cursorModel, viewSize,
                        cursorPressed = cursorPressed,
                        onCursor = { x, y -> cursorX = x; cursorY = y; cursorAlpha = 1f },
                        onPress = { pressed, x, y ->
                            cursorPressed = pressed
                            if (pressed) cursorRipple = RippleSpot(androidx.compose.ui.geometry.Offset(x, y), System.nanoTime())
                        },
                        onDpad = { center ->
                            val now = System.currentTimeMillis()
                            if (center) {
                                dpadPresses.clear()
                            } else {
                                dpadPresses.addLast(now)
                                while (dpadPresses.isNotEmpty() && now - dpadPresses.first() > DPAD_FOCUS_HINT_MS) dpadPresses.removeFirst()
                                if (dpadPresses.size >= DPAD_HINT_PRESSES) control.hintOnce(ControlNotice.DpadNoFocus)
                            }
                        },
                    )
                } else if (e.type == KeyEventType.KeyUp && code == KeyEvent.KEYCODE_MENU && (controlling || controlEntry)) {
                    // 菜单键先打开控制条（交互稿 13 v1.0.1）
                    controlBar = !controlBar
                    barInteraction = System.currentTimeMillis()
                    true
                } else if (e.type == KeyEventType.KeyUp && code == KeyEvent.KEYCODE_MENU) {
                    overlay = !overlay
                    lastInteraction = System.currentTimeMillis()
                    true
                } else {
                    if (overlay) lastInteraction = System.currentTimeMillis()
                    false
                }
            }
            .focusable()
            // 未控制时：单击画面 = 切换浮层（原行为）；控制中单击 = 对方点击，由 RemoteTouchLayer 处理
            .clickable(enabled = !controlling, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                overlay = !overlay
                lastInteraction = System.currentTimeMillis()
            }
            .onSizeChanged { viewSize = it },
    ) {
        VideoSurface(eglContext, attach, detach)
        if (controlling && control != null && !session.reconnecting) {
            RemoteTouchLayer(
                ctl, control,
                onNotice = { notice = it },
                pinchHintShown = pinchHintShown,
                onPinchHintShown = onPinchHintShown,
            )
        }
        // 交互稿 R10：TV 光标（本地绘制，限制在画面内容区）
        if (cursorOn && !session.reconnecting) {
            cursorRipple?.let { r -> Ripple(r) { cursorRipple = null } }
            // 3 s 无操作在 300 ms 内淡至 40%；系统关闭动画时立即切换（UX 走查 G5）
            val reduceMotion = remember {
                android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }
            val shownAlpha by androidx.compose.animation.core.animateFloatAsState(
                cursorAlpha, androidx.compose.animation.core.tween(if (reduceMotion) 0 else 300), label = "cursorAlpha",
            )
            TvCursor(cursorX, cursorY, shownAlpha)
        }

        // 14：重连中，最后一帧降亮度到 50% + 居中胶囊
        // 首帧前不显示“重连 / 最后一帧”（从未有过画面，E3）
        if (session.reconnecting && session.firstFrame) {
            Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = ScrimAlpha.Text)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = WscTheme.extended.warning)
                        Text(stringResource(R.string.rx_reconnecting), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Text(stringResource(R.string.rx_reconnecting_detail, session.peerName, session.reconnectAttempt.coerceAtLeast(1)), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                Text(stringResource(R.string.rx_last_frame), Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp), style = MaterialTheme.typography.bodySmall, color = c.onScrim)
            }
        } else if (!session.firstFrame) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.rx_receiving_from, session.peerName), color = c.onScrim, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // F18：会话中切换档位（媒体重建，≤1.5 秒目标）：保留最后一帧，居中小胶囊提示，不压暗画面
        // 超过 300 ms 才显示（多数切换很快结束，不打扰），不抢焦点、不响应按键
        var switchCard by remember { mutableStateOf(false) }
        LaunchedEffect(session.switching) {
            switchCard = false
            if (session.switching) {
                kotlinx.coroutines.delay(SWITCH_CARD_DELAY_MS)
                switchCard = true
            }
        }
        if (switchCard && session.switching && session.firstFrame && !session.reconnecting) {
            Row(
                Modifier.align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    PlaybackCopy.switching(session.gameMode).asString(), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        // F18：进入游戏模式时右上角“游戏模式”标签 3 秒，不遮挡画面中心、不可聚焦（不抢 TV 焦点）
        var gameBadge by remember { mutableStateOf(false) }
        val gameReady = session.gameMode && session.firstFrame && !session.switching
        LaunchedEffect(gameReady) {
            if (gameReady) {
                gameBadge = true
                kotlinx.coroutines.delay(GAME_BADGE_MS)
            }
            gameBadge = false
        }
        // 自动降帧：同位置 3 秒“已降为 30 帧”（原因留在手机端卡片里讲，电视上只说结果）
        // 两者同时成立时先显示“游戏模式”，结束后再显示“已降为 30 帧”（UX 走查 P2-10）
        var fpsBadge by remember { mutableStateOf(false) }
        LaunchedEffect(session.fpsCapped) {
            if (session.fpsCapped) {
                while (gameBadge) kotlinx.coroutines.delay(100)
                fpsBadge = true
                kotlinx.coroutines.delay(GAME_BADGE_MS)
            }
            fpsBadge = false
        }
        val badgeText = when {
            gameBadge -> PlaybackCopy.GAME_MODE_BADGE
            fpsBadge -> PlaybackCopy.FPS_CAPPED_BADGE
            else -> null
        }
        // TV 3 米外可读：16 sp（labelLarge 在 TV 字号体系里是 16 sp）、图标 20 dp（UX 走查 P1-5）
        val badgeStyle = if (isTv) MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp) else MaterialTheme.typography.labelLarge
        val badgeA11y = PlaybackCopy.badgeA11y(badgeText).asString()
        val badgeLabel = badgeText?.asString().orEmpty()
        AnimatedVisibility(badgeText != null && !overlay, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 12.dp)
                    .background(c.scrim.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp))
                    .padding(horizontal = if (isTv) 14.dp else 10.dp, vertical = 6.dp)
                    // 读屏播报“已切换到游戏模式”“已降为 30 帧”（UX 走查 P2-11）
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = badgeA11y
                    },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (badgeText == PlaybackCopy.GAME_MODE_BADGE) Icon(Icons.Rounded.SportsEsports, null, Modifier.size(20.dp), tint = c.onScrim)
                Text(badgeLabel, color = c.onScrim, style = badgeStyle)
            }
        }

        // S-02：单应用共享且该应用退到后台 → 占位画面而不是黑屏
        if (!session.appVisible && !session.reconnecting) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.AppsOutage, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.rx_app_hidden_title), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.rx_app_hidden_detail), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 15：受保护内容提示（推断，交互稿 Q3 / Q12）
        if (cardShown) {
            ProtectedCard(Modifier.align(Alignment.Center)) { protectedDismissed = true }
        }

        // 左上角角标行：控制中角标与“无声音”角标并排，不重叠（UX 走查 G10）
        val noAudio = session.audio == AudioState.NO_PERMISSION || session.audio == AudioState.OFF
        val pinnedLatency = session.stats.latencyMs?.takeIf { pinLatency && !overlay && session.firstFrame }
        if (noAudio || controlling || pinnedLatency != null) {
            Row(
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // F18 G05：一直显示延迟（左上，避开“游戏模式”标签与浮层），每秒随统计更新
                if (pinnedLatency != null) {
                    Row(
                        Modifier.background(c.scrim.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(WscTheme.extended.latency(session.stats.grade), androidx.compose.foundation.shape.CircleShape))
                        val pinnedA11y = PlaybackCopy.latencyA11y(pinnedLatency, session.gameMode).asString()
                        Text(
                            stringResource(R.string.rx_latency_pinned, pinnedLatency), color = c.onScrim,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontFeatureSettings = "tnum"),
                            modifier = Modifier.semantics { contentDescription = pinnedA11y },
                        )
                    }
                }
                if (controlling) WscAppTheme(darkTheme = true) {
                    ControlBadge(ctl, modeLabel = if (isTv) stringResource(if (effectiveMode == TvControlMode.DPAD) R.string.rx_mode_dpad else R.string.rx_mode_cursor) else null, tv = isTv)
                }
                // 录音权限被拒 → “无声音”角标
                if (noAudio) {
                    Row(
                        Modifier.background(c.scrim.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Rounded.VolumeOff, null, Modifier.size(16.dp), tint = c.onScrim)
                        Text(stringResource(R.string.rx_no_audio), color = c.onScrim, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        AnimatedVisibility(hint && session.firstFrame && !overlay, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
            Text(
                PlaybackCopy.firstFrameHint(isTv, controlEntry, controlling).asString(),
                Modifier.padding(horizontal = 48.dp, vertical = 27.dp).background(c.scrim.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                color = c.onScrim, style = MaterialTheme.typography.bodySmall,
            )
        }

        AnimatedVisibility(overlay, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
            StatsOverlay(session, isTv, onDisconnect = onDisconnect, onClose = { overlay = false }, pinLatency = pinLatency, onPinLatency = { pinLatency = it })
        }

        // —— 反向控制：角标、边缘把手、控制条、提示（播放页固定深色方案，交互稿 R06）——
        if (control != null && (controlEntry || controlling)) {
            WscAppTheme(darkTheme = true) {
                if (session.firstFrame && !controlBar && !isTv) {
                    // 避开挖孔（横屏时挖孔可能在右侧，UX 走查 G1）
                    ControlEdgeHandle(controlling, onOpen = {
                        controlBar = true
                        barInteraction = System.currentTimeMillis()
                    }, Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.displayCutout))
                }
                if (isTv) {
                    AnimatedVisibility(controlBar, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                        TvControlBar(
                            peer = session.peerName,
                            control = ctl,
                            mode = effectiveMode,
                            actions = control,
                            onDisconnect = onDisconnect,
                            onShowStats = {
                                controlBar = false
                                overlay = true
                                lastInteraction = System.currentTimeMillis()
                            },
                            onClose = { controlBar = false },
                            // 切换模式即时生效：收起控制条，方向键立刻交给光标 / 对方焦点（控制条开着时按键归控制条）
                            onMode = { m ->
                                onTvMode(m)
                                controlBar = false
                            },
                            onTextInput = {
                                controlBar = false
                                textDialog = true
                            },
                            onInteraction = { barInteraction = System.currentTimeMillis() },
                            modifier = Modifier.padding(horizontal = 48.dp, vertical = 27.dp).onSizeChanged { barHeightPx = it.height },
                        )
                    }
                    if (hintCard) {
                        TvControlHintCard(onDismiss = {
                            hintDismissed = true
                            onTvHintShown()
                        }, Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 27.dp), mode = effectiveMode)
                    }
                } else AnimatedVisibility(controlBar, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                    ControlBar(
                        peer = session.peerName,
                        control = ctl,
                        actions = control,
                        onDisconnect = onDisconnect,
                        onInteraction = { barInteraction = System.currentTimeMillis() },
                        onClose = { controlBar = false },
                        onShowStats = {
                            controlBar = false
                            overlay = true
                            lastInteraction = System.currentTimeMillis()
                        },
                        onTextInput = {
                            controlBar = false
                            textDialog = true
                        },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 14.dp)
                            .onSizeChanged { barHeightPx = it.height },
                    )
                }
                if (textDialog) {
                    RemoteTextDialog(
                        peer = session.peerName,
                        draft = textDraft,
                        onDraftChange = {
                            textDraft = it
                            textError = null
                        },
                        error = textError,
                        tv = isTv,
                        onSend = { t ->
                            // 不能发送（暂停 / 已结束）时保留对话框与草稿并说明（UX G17 / PM G2）
                            if (session.control.phase != com.benchan.wireless.screencasting.core.protocol.control.ControlPhase.ACTIVE) {
                                textError = textPausedMessage
                            } else {
                                control.sendText(t)
                                lastTextSentAt = System.currentTimeMillis()
                                textError = null
                                textDialog = false
                            }
                        },
                        onDismiss = {
                            textError = null
                            textDialog = false
                        },
                    )
                }
                notice?.let { n ->
                    val side = if (isTv) 48.dp else 16.dp
                    val gap = if (isTv) 27.dp else 24.dp
                    ControlNoticeToast(
                        n,
                        Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                            // 提示放在控制条上方（按控制条实际高度，UX 建议 A9）；TV 48 / 27 dp 安全边距（UX G9）
                            .padding(start = side, end = side, bottom = if (controlBar) with(density) { barHeightPx.toDp() } + gap else gap),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(eglContext: EglBase.Context, attach: (VideoSink) -> Unit, detach: (VideoSink) -> Unit) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            // 只在投屏中保持屏幕常亮（PRD 9.4）
            keepScreenOn = true
        }
    }
    DisposableEffect(renderer) {
        attach(renderer)
        onDispose {
            detach(renderer)
            renderer.release()
        }
    }
    // 等比显示、留黑边（PRD R-03）：SurfaceViewRenderer 的 SCALE_ASPECT_FIT 只在 onMeasure 里生效——它按画面比例量出
    // 比可用区域小的尺寸；而 EglRenderer 与硬件缩放（setFixedSize）都会把画面裁成“视图自身的比例”。
    // 如果直接 fillMaxSize，Compose 给的是 EXACTLY 约束，视图被撑满全屏，画面比例与屏幕不同时就被放大裁切
    // （竖屏手机 → 横屏电视时上下被裁掉）。这里给宽松约束（AT_MOST）并居中：旋转 / 显示尺寸变化时 Compose 重新测量，
    // 画面分辨率变化（例如切到游戏档、对方旋转）时 SurfaceViewRenderer 自己 requestLayout，都会重新等比布局。
    // 触点换算（InputGeometry.videoRectInView）按外层容器尺寸居中等比计算，与这里的布局一致。
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView({ renderer }, Modifier.wrapContentSize())
    }
}

/**
 * 交互稿 13 / 15 G05 统计浮层：第一行是唯一关心的数“延迟约 85 ms”（估算），随后四段耗时、帧率、画质、解码是否低延迟、
 * “一直显示延迟”开关；其余行（来源、协议、码率、丢帧、折线）放在下半，可滚动，保证第一屏不超过 1/3 画面。
 */
@Composable
private fun StatsOverlay(
    session: MirrorSession,
    isTv: Boolean,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
    pinLatency: Boolean = false,
    onPinLatency: (Boolean) -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val s = session.stats
    val closeFocus = remember { FocusRequester() }
    val modalVisible = LocalModalOverlayVisible.current
    // 默认焦点“关闭”（交互稿 13）。从控制条“连接信息”打开时，控制条淡出与本浮层组合在同一帧发生，
    // 系统会把焦点交给离原焦点最近的按钮（“断开投屏”），一按确定就断开。等两帧（控制条已移除、本浮层已布局）后再请求
    LaunchedEffect(modalVisible) {
        if (!modalVisible) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { closeFocus.requestFocus() }
        }
    }
    // 游戏档：第一屏高度不超过画面的 1/3（产品拍板按高度算），其余内容滚动可见（UX 走查 P2-12）
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val maxH = if (session.gameMode) screenH / 3 else 520.dp
    Column(
        Modifier.padding(horizontal = 48.dp, vertical = 27.dp).widthIn(max = 380.dp)
            .background(c.surfaceContainerHigh.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .heightIn(max = maxH).verticalScroll(rememberScrollState()).padding(20.dp)
            // 焦点留在浮层内（方向键不跑到画面 / 控制条残影上）
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.rx_stats_title), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
            Text(PlaybackCopy.overlayCloseHint(isTv).asString(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        // 第一行：延迟约 xx ms（24 sp + 色阶圆点），下一行“估算”（不显示目标值）
        val latencyA11y = PlaybackCopy.latencyA11y(s.latencyMs, session.gameMode).asString()
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = latencyA11y }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(ext.latency(s.grade), androidx.compose.foundation.shape.CircleShape))
                Text(
                    PlaybackCopy.latencyHeadline(s.latencyMs).asString(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), color = c.onSurface,
                )
                s.grade?.let { Text(it.displayLabel(), style = MaterialTheme.typography.bodyMedium, color = ext.latency(s.grade)) }
            }
            Text(PlaybackCopy.latencySubline(session.gameMode).asString(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        s.breakdown?.let { b -> LatencySegments(b) }
        StatRow(stringResource(R.string.rx_stat_fps), if (s.fps > 0) stringResource(R.string.rx_stat_fps_value, s.fps) else "—")
        StatRow(stringResource(R.string.rx_stat_quality), PlaybackCopy.quality(s.height, session.profile, session.gameMode, session.senderLegacy).asString())
        val hwLabel = s.hardwareDecoder?.let { stringResource(if (it) R.string.rx_decoder_hardware else R.string.rx_decoder_software) }
        val llLabel = s.lowLatencyDecoder?.let { stringResource(if (it) R.string.rx_decoder_low_latency else R.string.rx_decoder_no_low_latency) }
        StatRow(
            stringResource(R.string.rx_stat_decoder),
            listOfNotNull(hwLabel, llLabel).joinToString(" · ").ifBlank { s.codec ?: s.decoder ?: "—" },
        )
        // “一直显示延迟”（默认关，只在本次投屏有效）；方向键上移可达
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(12.dp)
                .toggleable(value = pinLatency, role = Role.Switch, onValueChange = onPinLatency)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rx_pin_latency), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = pinLatency, onCheckedChange = null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(stringResource(R.string.rx_close), onClose, Modifier.focusRequester(closeFocus))
            TvButton(stringResource(R.string.rx_disconnect), onDisconnect)
        }
        // —— 下半：其余信息 ——
        StatRow(stringResource(R.string.rx_stat_source), session.peerName)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rx_stat_protocol), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = c.tertiary)
                Text(stringResource(R.string.rx_protocol_mirror_encrypted), style = MaterialTheme.typography.bodyMedium)
            }
        }
        StatRow(stringResource(R.string.rx_stat_resolution), if (s.width > 0) "${s.width} × ${s.height}" else "—")
        StatRow(stringResource(R.string.rx_stat_bitrate), if (s.bitrateKbps > 0) formatMbps(s.bitrateKbps) else "—")
        StatRow(stringResource(R.string.rx_stat_dropped), String.format(Locale.US, "%.1f %%", s.dropPercent))
        StatRow(stringResource(R.string.rx_stat_codec), s.codec ?: s.decoder ?: "—")
        LatencySparkline(s.latencyHistory, Modifier.fillMaxWidth().height(56.dp))
        Text(stringResource(R.string.rx_sparkline_caption, stringResource(LatencyGrade.EXCELLENT.labelRes), stringResource(LatencyGrade.GOOD.labelRes)), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/** 四段耗时：一条分段条 + 名称与数值（颜色只区分顺序，不靠颜色读数，交互稿 G05）。 */
@Composable
private fun LatencySegments(b: LatencyBreakdown) {
    val c = MaterialTheme.colorScheme
    val parts = listOf(
        stringResource(R.string.rx_seg_network) to (b.networkMs to c.primary),
        stringResource(R.string.rx_seg_buffer) to (b.bufferMs to c.tertiary),
        stringResource(R.string.rx_seg_decode) to (b.decodeMs to c.secondary),
        stringResource(R.string.rx_seg_display) to (b.displayMs to c.outline),
    )
    val total = parts.sumOf { it.second.first }.coerceAtLeast(1.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            parts.forEach { (_, v) ->
                val w = (v.first / total).toFloat().coerceAtLeast(0.02f)
                Box(Modifier.weight(w).fillMaxHeight().background(v.second))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            parts.forEach { (name, v) ->
                Column {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    Text("${v.first.toInt()} ms", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
    }
}

/** 交互稿 15：先排除“故障”归因，再给替代路径；不出现任何“绕过”字样。 */
@Composable
private fun ProtectedCard(modifier: Modifier, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val modalVisible = LocalModalOverlayVisible.current
    LaunchedEffect(modalVisible) { if (!modalVisible) runCatching { focus.requestFocus() } }
    Column(
        modifier.widthIn(max = 560.dp).background(c.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Shield, null, Modifier.size(36.dp), tint = c.tertiary)
        Text(stringResource(R.string.rx_protected_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.rx_protected_detail), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        Column(Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text(stringResource(R.string.rx_protected_push_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rx_protected_push_detail), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
        TvButton(stringResource(R.string.rx_got_it), onDismiss, Modifier.focusRequester(focus))
        Box(Modifier.width(1.dp))
    }
}

/** 播放页提示文案按设备类型切换：TV 用遥控器，触屏设备（平板 / 手机当接收端）用轻点（C12）。文字在资源里（英文默认 / values-zh）。 */
internal object PlaybackCopy {
    /** TV 按状态提示（UX 走查 G10）：有控制入口未控制 / 控制中 / 没有控制入口。 */
    fun firstFrameHint(isTv: Boolean, controlEntry: Boolean = false, controlling: Boolean = false): UiText = uiText(
        when {
            isTv && controlling -> R.string.rx_hint_tv_controlling
            isTv && controlEntry -> R.string.rx_hint_tv_control_entry
            isTv -> R.string.rx_hint_tv
            else -> R.string.rx_hint_touch
        },
    )

    fun overlayCloseHint(isTv: Boolean): UiText = uiText(if (isTv) R.string.rx_overlay_close_tv else R.string.rx_overlay_close_touch)

    // —— F18 游戏模式（交互稿 G 章节完成前的最小可用文案）——
    val GAME_MODE_BADGE: UiText = uiText(R.string.rx_badge_game_mode)
    val FPS_CAPPED_BADGE: UiText = uiText(R.string.rx_badge_fps_capped)

    fun latencyHeadline(ms: Int?): UiText = ms?.let { uiText(R.string.rx_latency_headline, it) } ?: uiText(R.string.rx_latency_headline_unknown)

    /** 界面不显示目标延迟数字（产品拍板），只写“估算”。 */
    @Suppress("UNUSED_PARAMETER")
    fun latencySubline(game: Boolean): UiText = uiText(R.string.rx_latency_estimated)

    @Suppress("UNUSED_PARAMETER")
    fun latencyA11y(ms: Int?, game: Boolean): UiText = ms?.let { uiText(R.string.rx_latency_a11y, it) } ?: uiText(R.string.rx_latency_a11y_unknown)

    fun badgeA11y(badge: UiText?): UiText = when (badge) {
        GAME_MODE_BADGE -> uiText(R.string.rx_badge_game_mode_a11y)
        FPS_CAPPED_BADGE -> uiText(R.string.rx_badge_fps_capped)
        else -> UiText.Raw("")
    }

    /**
     * “720p · 游戏模式”/“1080p · 均衡”。发送端选了游戏档但本机接收链路没有按游戏模式建立时如实标注（PM 走查 P1-8）：
     * 新版发送端（做不到即时）→“低延迟缓冲：下次投屏生效”；旧版发送端 →“低延迟未开启”。
     */
    fun quality(height: Int, profile: com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile?, game: Boolean, senderLegacy: Boolean = false): UiText {
        val name: UiText? = when {
            game -> uiText(R.string.rx_badge_game_mode)
            profile?.lowLatency == true ->
                uiText(if (senderLegacy) R.string.rx_quality_low_latency_off else R.string.rx_quality_low_latency_next, uiText(profile.labelRes))
            else -> profile?.let { uiText(it.labelRes) }
        }
        val res = height.takeIf { it > 0 }?.let { "${it}p" }
        return when {
            res != null && name != null -> uiText(R.string.rx_quality_with_resolution, res, name)
            res != null -> UiText.Raw(res)
            name != null -> name
            else -> UiText.Raw("—")
        }
    }

    fun switching(toGame: Boolean): UiText = uiText(if (toGame) R.string.rx_switching_game else R.string.rx_switching_quality)

}

/**
 * 控制中的按键（遥控器 / 外接键盘，PRD F23 B 期；返回键与菜单键另行处理）：经 [KeyMapper] 映射，按对方能力降级。
 * 确定 / Esc / Meta 等离散操作只在首个按下（repeatCount == 0）发送，按住不重复（PM / UX 走查 S3）；方向键导航允许按键重复。
 * @return 是否消费该按键。
 */
private fun handleControlKey(
    ev: KeyEvent,
    isTv: Boolean,
    mode: TvControlMode,
    features: Set<String>,
    control: RemoteControlActions,
    cursor: TvCursorModel,
    viewSize: androidx.compose.ui.unit.IntSize,
    cursorPressed: Boolean,
    onCursor: (Float, Float) -> Unit,
    onPress: (Boolean, Float, Float) -> Unit,
    onDpad: (center: Boolean) -> Unit,
): Boolean {
    val down = ev.action == KeyEvent.ACTION_DOWN
    val first = down && ev.repeatCount == 0
    val now = android.os.SystemClock.uptimeMillis()
    cursor.touch(now)
    var input = ev.toKeyInput(isTv)
    // 兜底（待真机确认）：部分遥控器的“确定”发送 KEYCODE_ENTER。TV 光标模式下，若按键不是来自全键盘，
    // 或发送端最近回过“没有选中的输入框”，把 ENTER 当作确定键（光标处点击）
    if (isTv && mode == TvControlMode.CURSOR && input.key == com.benchan.wireless.screencasting.core.control.InKey.ENTER &&
        (control.editorMissing || ev.device?.keyboardType != android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC)
    ) {
        input = input.copy(key = com.benchan.wireless.screencasting.core.control.InKey.CENTER, source = com.benchan.wireless.screencasting.core.control.KeySource.REMOTE)
    }
    return when (val a = KeyMapper.map(input, mode, features)) {
        is KeyAction.Cursor -> {
            // 首个按下可能被系统吞掉（例如触屏设备退出触摸模式），之后只有 repeatCount > 0 的重复：
            // 该方向尚未按住时把重复也当作按下，按住移动与边缘滚动才不会失效
            if (down && (first || !cursor.isHeld(a.dir))) {
                WscLog.d(CURSOR_TAG, "cursor key down ${a.dir}${if (first) "" else " (from repeat)"}")
                cursor.keyDown(a.dir, now)?.let { edge ->
                    WscLog.d(CURSOR_TAG, "edge scroll on press ${if (edge.dy > 0) "down" else "up"}")
                    control.wheel(viewSize.width, viewSize.height, cursor.x.toFloat(), cursor.y.toFloat(), edge.dy)
                }
                onCursor(cursor.x.toFloat(), cursor.y.toFloat())
                if (cursorPressed) control.pointerMove(viewSize.width, viewSize.height, cursor.x.toFloat(), cursor.y.toFloat())
            } else if (ev.action == KeyEvent.ACTION_UP) {
                WscLog.d(CURSOR_TAG, "cursor key up ${a.dir}")
                cursor.keyUp(a.dir, now)
            }
            true
        }
        KeyAction.CursorPress -> {
            val x = cursor.x.toFloat()
            val y = cursor.y.toFloat()
            // 同上：首个按下被吞掉时，第一次重复视为按下
            if (first || (down && ev.repeatCount == 1 && !cursorPressed)) {
                WscLog.d(CURSOR_TAG, "cursor press")
                onPress(control.pointerDown(viewSize.width, viewSize.height, x, y), x, y)
            } else if (ev.action == KeyEvent.ACTION_UP && cursorPressed) {
                control.pointerUp(viewSize.width, viewSize.height, x, y)
                onPress(false, x, y)
            }
            true
        }
        is KeyAction.Send -> {
            val navigation = a.key in setOf(RemoteKey.DPAD_UP, RemoteKey.DPAD_DOWN, RemoteKey.DPAD_LEFT, RemoteKey.DPAD_RIGHT, RemoteKey.DEL)
            if (navigation && down || first) {
                control.key(a.key)
                if (a.key in RemoteKey.DPAD) onDpad(a.key == RemoteKey.DPAD_CENTER)
            } else if (down && a.key == RemoteKey.DPAD_CENTER && ev.eventTime - ev.downTime >= LONG_PRESS_MS) {
                // 方向键模式不支持长按：提示一次换光标模式
                control.hintOnce(ControlNotice.DpadLongPress)
            }
            true
        }
        is KeyAction.Type -> {
            if (down) control.typeChar(a.char)
            true
        }
        is KeyAction.Unsupported -> {
            if (first) control.notifyUnsupported(a.feature)
            true
        }
        KeyAction.Back, KeyAction.ToggleBar, KeyAction.Ignore -> false
    }
}

private const val CURSOR_TAG = "TvCursor"

/** F18：“游戏模式”标签显示时长（PRD 8.1.3 E：3 秒）。 */
private const val GAME_BADGE_MS = 3_000L

/** F18：切换过渡小卡的出现延迟（交互稿 G05：超过 300 ms 才显示）。 */
private const val SWITCH_CARD_DELAY_MS = 300L

/** 方向键模式“看不到焦点？”的触发次数（5 s 内）。 */
private const val DPAD_HINT_PRESSES = 6

/** 长按判定（与系统一致的 500 ms）。 */
private const val LONG_PRESS_MS = 500L
