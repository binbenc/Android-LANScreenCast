package com.benchan.wireless.screencasting.feature.sender.ui

import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.net.LanInfo
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.ui.components.NumericKeypad
import com.benchan.wireless.screencasting.core.ui.components.PinCells
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import kotlinx.coroutines.delay

/**
 * PIN 类页面布局：竖屏 = 标题栏 + 可滚动内容 + 底部键盘；横屏 = 标题栏 + 左内容（可滚动）/ 右键盘，
 * 避免键盘把 PIN 格子挤出屏幕（C11）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinPageLayout(
    title: String,
    onBack: () -> Unit,
    keypad: @Composable (Modifier) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.sender_back)) } },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight
            val body = @Composable { m: Modifier ->
                Column(
                    m.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    content()
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    body(Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        keypad(Modifier.navigationBarsPadding())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    body(Modifier.weight(1f).fillMaxWidth())
                    keypad(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/**
 * 交互稿 05A：输入电视上显示的 4 位 PIN。输满第 4 位自动提交；错误时抖动 + 清空；
 * 倒计时与 TV 弹窗同步（30 秒），剩余 10 秒转为 warning 色。
 */
@Composable
internal fun PinEntryScreen(state: SenderState.AwaitingPin, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var errorKey by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.error, state.attemptsLeft) {
        if (state.error) {
            pin = ""
            errorKey = System.currentTimeMillis()
        }
    }
    val remaining = rememberCountdown(state.deadlineAt)
    BackHandler(onBack = onCancel)
    PinPageLayout(
        title = stringResource(R.string.sender_pin_title, state.target.name),
        onBack = onCancel,
        keypad = { m ->
            NumericKeypad(
                onDigit = { d ->
                    if (pin.length < 4 && !state.verifying) {
                        pin += d
                        if (pin.length == 4) onSubmit(pin)
                    }
                },
                onBackspace = { pin = pin.dropLast(1) },
                onCancel = onCancel,
                modifier = m,
            )
        },
    ) {
        Text(stringResource(R.string.sender_pin_heading), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.sender_pin_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // PIN 是秘密：读屏只报告位数
        PinCells(pin, 4, Modifier.fillMaxWidth().wrapContentWidth(), error = state.error && pin.isEmpty(), errorKey = errorKey, secret = true)
        if (state.error) {
            Text(
                state.attemptsLeft?.let { pluralStringResource(R.plurals.sender_pin_wrong_left, it, it) } ?: stringResource(R.string.sender_pin_wrong),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.verifying) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            // 倒计时不设 liveRegion：读屏不会每秒播报（C13）
            Text(
                pluralStringResource(R.plurals.sender_pin_waiting, remaining, remaining, state.target.name),
                style = MaterialTheme.typography.bodySmall,
                color = if (remaining <= 10) WscTheme.extended.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 交互稿 05B：8 位投屏码，3+5 分组；输入前 3 位即实时显示解析出的 IP；网段不支持时禁用数字键并提示改用扫码；输满即连接。
 */
@Composable
internal fun CastCodeScreen(lan: LanInfo?, onConnect: (CastCode) -> Boolean, onManualIp: () -> Unit, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errorKey by remember { mutableLongStateOf(0L) }
    BackHandler(onBack = onBack)
    val networkHint = SenderCopy.castCodeNetworkHint(hasLan = lan != null, prefixSupported = lan != null && CastCode.supportsNetwork(lan.prefixLength))
    val preview = if (lan != null && code.length >= 3) CastCode.resolveHost(lan.address.address, lan.prefixLength, code.take(3)) else null
    val invalidCode = stringResource(R.string.sender_cast_code_invalid)
    PinPageLayout(
        title = stringResource(R.string.sender_cast_code_title),
        onBack = onBack,
        keypad = { m ->
            NumericKeypad(
                onDigit = { d ->
                    if (code.length < 8) {
                        code += d
                        error = null
                        if (code.length == 8) {
                            val parsed = CastCode.parse(code)
                            if (parsed == null || !onConnect(parsed)) {
                                error = invalidCode
                                errorKey = System.currentTimeMillis()
                                code = ""
                            }
                        }
                    }
                },
                onBackspace = { code = code.dropLast(1); error = null },
                onCancel = onBack,
                modifier = m,
                digitsEnabled = networkHint == null,
            )
        },
    ) {
        Text(stringResource(R.string.sender_cast_code_hint), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        // 投屏码不是秘密：读屏读出已输入的数字
        PinCells(code, 8, Modifier.fillMaxWidth().wrapContentWidth(), groups = listOf(3, 5), error = error != null, errorKey = errorKey, secret = false)
        when {
            networkHint != null -> Text(networkHint.asString(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            preview != null -> Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.sender_cast_code_preview, preview), style = MaterialTheme.typography.bodyMedium)
            }
            code.length >= 3 -> Text(stringResource(R.string.sender_cast_code_range), color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        TextButton(onClick = onManualIp) { Text(stringResource(R.string.sender_cast_code_manual_ip)) }
    }
}

/** 正在连接 / 等待电视确认（可信设备“每次都确认”）。整页、不透明，吃掉触摸。 */
@Composable
internal fun WaitingScreen(title: String, detail: String, deadlineAt: Long?, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val remaining = deadlineAt?.let { rememberCountdown(it) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) { detectTapGestures { } }
            .systemBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            remaining?.let { Text(pluralStringResource(R.plurals.sender_auto_cancel, it, it), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.sender_cancel)) }
        }
    }
}

@Composable
internal fun rememberCountdown(deadlineAt: Long): Int {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadlineAt) {
        while (System.currentTimeMillis() < deadlineAt) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    return ((deadlineAt - now) / 1000).toInt().coerceAtLeast(0)
}
