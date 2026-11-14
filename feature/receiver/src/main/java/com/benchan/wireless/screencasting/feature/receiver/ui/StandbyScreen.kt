package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.ui.components.QrCode
import com.benchan.wireless.screencasting.core.ui.components.TvButton
import com.benchan.wireless.screencasting.core.ui.theme.PinStyleTv
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.receiver.session.StandbyInfo
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 交互稿 11：待机页 · 等待投屏。左 = 身份与步骤（读），右 = 连接凭据（扫 / 抄）。
 * 页面只有 2 个可聚焦元素（去开启、设置），默认焦点在“去开启”（仅 Apple 兼容关闭时显示该卡），否则在设置。
 * 5% 过扫描安全区：水平 48dp、垂直 27dp。
 */
@Composable
internal fun StandbyScreen(info: StandbyInfo, onOpenSettings: () -> Unit, onEnableAppleCompat: () -> Unit, appleFailure: String? = null) {
    val c = MaterialTheme.colorScheme
    val settingsFocus = remember { FocusRequester() }
    val appleFocus = remember { FocusRequester() }
    val showApple = info.appleCompatAvailable && !info.appleCompatEnabled
    LaunchedEffect(showApple) {
        runCatching { if (showApple) appleFocus.requestFocus() else settingsFocus.requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface).systemBarsPadding().padding(horizontal = 48.dp, vertical = 27.dp)) {
        val wide = maxWidth > maxHeight
        val left = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("局域投屏", style = MaterialTheme.typography.titleLarge, color = c.primary)
                    Text("无广告 · 无账号 · 不连接互联网", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Text("等待投屏", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
                Text(info.deviceName, style = MaterialTheme.typography.displayMedium, color = c.onSurface)
                if (!info.lanAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.WifiOff, null, tint = WscTheme.extended.warning)
                        Text("没有连接网络：请把电视连到家里的路由器", style = MaterialTheme.typography.bodyLarge, color = WscTheme.extended.warning)
                    }
                }
                Text("手机与电视连接同一个路由器后：", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                Step(1, "手机打开本应用，选择「${info.deviceName}」")
                Step(2, "搜不到时，扫描${if (wide) "右侧" else "下方"}二维码或输入投屏码")
                if (showApple) {
                    Row(
                        Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Rounded.Devices, null, Modifier.size(32.dp), tint = c.onSurfaceVariant)
                        Column(Modifier.weight(1f, fill = false)) {
                            Text("iPhone / iPad 用户", style = MaterialTheme.typography.titleMedium)
                            Text("在设置中开启“兼容 Apple 设备”后，用系统「屏幕镜像」即可连接", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        TvButton("去开启", onEnableAppleCompat, Modifier.focusRequester(appleFocus))
                    }
                }
                if (info.appleCompatEnabled && appleFailure != null) {
                    Text("兼容 Apple 设备未能启动：$appleFailure", style = MaterialTheme.typography.bodyMedium, color = WscTheme.extended.warning)
                }
                info.notice?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = WscTheme.extended.warning)
                }
            }
        }
        val right = @Composable {
            Column(
                Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(24.dp).widthIn(max = 320.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (info.qrContent != null) {
                    QrCode(info.qrContent, Modifier.size(200.dp), description = "扫码连接二维码")
                    Text("扫码连接 · 免输入 PIN", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Text("投屏码", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                val code = info.castCode
                Text(
                    code?.display ?: "— —",
                    style = PinStyleTv,
                    color = c.onSurface,
                    modifier = Modifier.semantics { contentDescription = "投屏码 ${code?.digits?.toList()?.joinToString(" ") ?: "不可用"}" },
                )
                if (code == null && info.lanAvailable) {
                    Text("当前网络不支持投屏码，请扫码", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                if (info.ip != null) {
                    Text("${info.ip} : ${info.port ?: "—"}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { left() }
                right()
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                left()
                right()
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            TvButton("设置", onOpenSettings, Modifier.focusRequester(settingsFocus), leading = { Icon(Icons.Rounded.Settings, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) })
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(c.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 防烧屏屏保（PRD R-01）：10 分钟无操作进入，只保留设备名 + 投屏码，每 60 秒移动一次位置（交互稿 Q10 的默认做法）。
 */
@Composable
internal fun ScreenSaver(info: StandbyInfo) {
    var seed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            seed++
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        val r = remember(seed) { Random(seed) }
        val x = (r.nextFloat() * (maxWidth.value - 360)).coerceAtLeast(0f)
        val y = (r.nextFloat() * (maxHeight.value - 160)).coerceAtLeast(0f)
        Column(Modifier.offset { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }) {
            Text(info.deviceName, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.castCode?.let { Text("投屏码 ${it.display}", style = PinStyleTv.copy(fontSize = PinStyleTv.fontSize * 0.6f), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
