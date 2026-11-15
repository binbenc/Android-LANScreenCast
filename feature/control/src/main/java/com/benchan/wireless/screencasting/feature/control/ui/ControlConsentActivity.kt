package com.benchan.wireless.screencasting.feature.control.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.rounded.Info
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.ui.theme.WscAppTheme
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.control.AccessibilityBridge
import com.benchan.wireless.screencasting.feature.control.ControlCopy
import com.benchan.wireless.screencasting.feature.control.ControlNotifications
import com.benchan.wireless.screencasting.feature.control.R
import com.benchan.wireless.screencasting.feature.control.SenderControlCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/**
 * 交互稿 R03：控制请求确认（每次投屏，可信设备也不跳过）。
 *
 * - 只有“允许本次 / 拒绝”，没有“始终允许”；两个按钮等宽；30 秒无响应 = 拒绝；返回键 = 拒绝。
 * - 本页显示期间（onStart~onStop）屏蔽来自接收端的任何注入，防止对方自己点“允许”。
 * - 不在锁屏上显示（锁屏 = 不可控制，发送端直接回复“已锁屏”）。
 */
@AndroidEntryPoint
class ControlConsentActivity : ComponentActivity() {
    @Inject
    lateinit var coordinator: SenderControlCoordinator

    @Inject
    lateinit var bridge: AccessibilityBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 从本应用内打开（例如投屏页）：允许后回到桌面，否则 D9 会让控制一直处于暂停（用户决策）
        val fromOwnApp = coordinator.ownAppInForeground()
        setContent {
            WscAppTheme {
                val ui by coordinator.ui.collectAsStateWithLifecycle()
                // 请求已结束（允许 / 拒绝 / 超时 / 对方取消 / 会话结束）→ 关闭本页，回到确认前的界面
                LaunchedEffect(ui.phase) { if (ui.phase != ControlPhase.PENDING) finish() }
                BackHandler { coordinator.deny() }
                ConsentScreen(
                    peer = ui.peerName,
                    deadline = ui.pendingDeadline,
                    onDeny = { coordinator.deny() },
                    onAllow = {
                        coordinator.allow()
                        if (fromOwnApp) ControlCopy.goHome(this)
                    },
                    fromOwnApp = fromOwnApp,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bridge.consentVisible = true
        // 确认页可见期间撤掉请求通知，避免悬浮通知盖住标题（真机问题 R2）；离开后协调器会静默重发
        ControlNotifications.cancelRequest(this)
    }

    override fun onStop() {
        bridge.consentVisible = false
        super.onStop()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ControlConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun ConsentScreen(peer: String, deadline: Long, onDeny: () -> Unit, onAllow: () -> Unit, fromOwnApp: Boolean) {
    val c = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val seconds = (((deadline - now) + 999) / 1_000).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            IconTile(painterResource(R.drawable.ic_stat_control))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.ctl_request_title, peer), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text(stringResource(R.string.ctl_consent_from_cast), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 顺序 = 风险 → 时效 → 退路 → 禁忌；最后一条 warning 图标，正文仍为 onSurface（不靠颜色传达）
                // 本机支持文字输入（Android 13+）时如实写出（两份走查）
                ConsentItem(
                    rememberVectorPainter(Icons.AutoMirrored.Rounded.ScreenShare), c.onSurfaceVariant,
                    stringResource(if (android.os.Build.VERSION.SDK_INT >= 33) R.string.ctl_consent_item_operate_text else R.string.ctl_consent_item_operate),
                )
                ConsentItem(rememberVectorPainter(Icons.Rounded.History), c.onSurfaceVariant, stringResource(R.string.ctl_consent_item_session))
                ConsentItem(painterResource(R.drawable.ic_control_handback), c.onSurfaceVariant, stringResource(R.string.ctl_consent_item_revoke))
                ConsentItem(rememberVectorPainter(Icons.Rounded.WarningAmber), WscTheme.extended.warning, stringResource(R.string.ctl_consent_item_warning))
            }
            // C 期：本机开着屏幕朗读（触摸浏览）时，卡片外的中性信息条（UX §4），实时监听开关
            if (touchExploration()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                    Text(ControlCopy.TALKBACK_CONSENT, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // D9 说明放在按钮上方（UX G10）
            if (fromOwnApp) {
                Text(stringResource(R.string.ctl_consent_go_home), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onDeny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.ctl_deny)) }
                Button(onClick = onAllow, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.ctl_allow_once)) }
            }
            Spacer(Modifier.height(8.dp))
            // 倒计时不做实时播报（每秒打断读屏），只在剩 10 秒时播报一次（UX 走查 G5）
            val tenLeft = stringResource(R.string.ctl_consent_ten_left)
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = if (seconds in 1..10) tenLeft else "" })
            Text(
                pluralStringResource(R.plurals.ctl_auto_deny, seconds.toInt(), seconds.toInt()),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = c.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 本机是否开着触摸浏览（TalkBack 等屏幕朗读）；实时监听开关变化（UX G4）。 */
@Composable
fun touchExploration(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val am = remember { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    androidx.compose.runtime.DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}

@Composable
private fun ConsentItem(icon: Painter, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 交互稿 iconTile：56 dp、圆角 18、primaryContainer。 */
@Composable
internal fun IconTile(icon: Painter, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.size(56.dp).background(c.primaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(28.dp), tint = c.onPrimaryContainer)
    }
}
