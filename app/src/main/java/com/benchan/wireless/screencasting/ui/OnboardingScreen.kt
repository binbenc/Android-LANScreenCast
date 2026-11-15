package com.benchan.wireless.screencasting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.data.settings.AppMode

/**
 * 交互稿 01：首次引导 · 承诺 + 模式选择。承诺写可验证的事实；本页不申请任何权限；模式卡单选、整卡可点（≥108dp）。
 */
@Composable
fun OnboardingScreen(defaultMode: AppMode, onStart: (AppMode) -> Unit, onInstallHelp: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(defaultMode) }
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(16.dp))
        Text("把手机画面，\n安静地送到大屏", style = MaterialTheme.typography.headlineLarge)
        Text("局域网直连 · GPL‑3.0 开源，代码可审计", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        Promise(Icons.Rounded.DoNotDisturbOn, "没有广告，永久免费", "不接入任何广告、统计、崩溃上报 SDK")
        Promise(Icons.Rounded.PersonOff, "不需要账号", "打开即用，不索要手机号")
        Promise(Icons.Rounded.CloudOff, "不连接互联网", "画面只在你家的局域网内传输，屏幕镜像全程加密")
        Text("这台设备用来", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ModeCard(mode == AppMode.SENDER, Icons.Rounded.ScreenShare, "投屏到别处", "发送端" + if (defaultMode == AppMode.SENDER) " · 本机推荐" else "") { mode = AppMode.SENDER }
        ModeCard(mode == AppMode.RECEIVER, Icons.Rounded.Tv, "接收投屏", "把本机当作屏幕" + if (defaultMode == AppMode.RECEIVER) " · 本机推荐" else "") { mode = AppMode.RECEIVER }
        Button(onClick = { onStart(mode) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("开始使用") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("电视还没装接收端？", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            TextButton(onClick = onInstallHelp) { Text("获取安装方法") }
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeCard(selected: Boolean, icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 108.dp)
            .background(if (selected) c.primaryContainer else c.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outlineVariant, RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = if (selected) c.onPrimaryContainer else c.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        RadioButton(selected, onClick = null)
    }
}
