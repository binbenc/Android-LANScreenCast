package com.benchan.wireless.screencasting.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.data.settings.AppMode
import com.benchan.wireless.screencasting.core.data.settings.ConnectionPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme

/** 设置页的跳转目标。 */
enum class SettingsDestination { TRUSTED, REPORT, DIAGNOSTICS, LOGS, INSTALL_HELP }

/**
 * 交互稿 09A：设置。按“发送 → 接收 → 诊断 → 关于”的任务频率排序，接收相关项在 TV 端置顶；
 * 每个开关都有一句“开了会怎样”的副标题；非官方声明常驻显示；加密不提供开关（决策 #15）。
 *
 * @param openAppleCompat 从 TV 待机页“去开启”进入时直接弹出 09B 确认框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isTv: Boolean,
    versionName: String,
    onBack: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    openAppleCompat: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val trusted by viewModel.trusted.collectAsStateWithLifecycle()
    val summary by viewModel.summaryForRange.collectAsStateWithLifecycle()
    val settings = s ?: return
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openAppleCompat) {
        if (openAppleCompat && viewModel.appleCompatAvailable && !settings.appleCompatEnabled) dialog = "apple"
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group("本机")
            NavRow("设备名", settings.deviceName) { dialog = "name" }
            NavRow("默认模式", if ((settings.mode ?: if (isTv) AppMode.RECEIVER else AppMode.SENDER) == AppMode.SENDER) "发送端（投屏到别处）" else "接收端（把本机当作屏幕）") { dialog = "mode" }

            val sender = @Composable {
                Group("投屏")
                NavRow("画质档位", settings.quality.label + if (settings.autoAdjustQuality) " · 网络变差时自动降档" else "") { dialog = "quality" }
                SwitchRow("自动调节", "网络变差时自动降为“流畅”", settings.autoAdjustQuality, viewModel::setAutoAdjust)
                SwitchRow("声音", "只捕获手机播放的声音，不录麦克风", settings.audioEnabled, viewModel::setAudio)
                SwitchRow("录屏前显示说明卡", "关闭后点设备直接弹出系统录屏授权", !settings.skipCaptureIntro, onChange = { viewModel.setSkipIntro(!it) })
            }
            val receiver = @Composable {
                Group("接收（本机作为屏幕时）")
                NavRow("连接策略", if (settings.connectionPolicy == ConnectionPolicy.TRUSTED_SKIP_CONFIRM) "已信任的设备免确认" else "每次都确认") { dialog = "policy" }
                NavRow("可信设备", "${trusted.size} 台 · 可逐个移除", Icons.Rounded.Devices) { onNavigate(SettingsDestination.TRUSTED) }
                SwitchRow("DLNA 接收", "视频 App 的“投屏”按钮可以选择本机", settings.dlnaEnabled, viewModel::setDlna)
                SwitchRow("陌生设备需确认", "防止邻居或酒店住客误投", settings.dlnaConfirmUnknown, viewModel::setDlnaConfirm, enabled = settings.dlnaEnabled)
                if (viewModel.appleCompatAvailable) {
                    SwitchRow(
                        "兼容 Apple 设备（非官方）",
                        "开启后 iPhone、iPad 可用系统“屏幕镜像”连接本机",
                        settings.appleCompatEnabled,
                        { on -> if (on) dialog = "apple" else viewModel.setAppleCompat(false, settings.appleCompatPin) },
                    )
                    // 非官方声明常驻显示（交互稿 09A）
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "基于社区开源实现，与 Apple Inc. 无关，未获其授权或认证；系统更新后可能暂时失效；不支持受版权保护的视频。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SwitchRow("开机自启", "开机后在后台等待投屏，会略微增加耗电；部分电视系统会限制后台启动", settings.bootStart, onChange = { on ->
                    if (on) dialog = "boot" else viewModel.setBootStart(false)
                })
            }
            if (isTv) { receiver(); sender() } else { sender(); receiver() }

            Group("诊断")
            NavRow(
                "投屏报告",
                summary.successRatePercent?.let { "最近 30 天成功率 $it% · 平均延迟 ${summary.avgLatencyMs ?: "—"} ms" } ?: "还没有投屏记录",
                Icons.Rounded.BarChart,
            ) { onNavigate(SettingsDestination.REPORT) }
            NavRow("网络诊断", "检查网段、组播与路由器设置", Icons.Rounded.Router) { onNavigate(SettingsDestination.DIAGNOSTICS) }
            NavRow("导出本地日志", "导出前可以先查看内容，用于提交 issue", Icons.Rounded.IosShare) { onNavigate(SettingsDestination.LOGS) }
            NavRow("把接收端安装到电视", "手机在局域网内临时提供安装包", Icons.Rounded.InstallMobile) { onNavigate(SettingsDestination.INSTALL_HELP) }

            Group("关于")
            InfoRow(Icons.Rounded.PrivacyTip, "隐私", "本应用不访问互联网，不上报任何数据；加密始终开启")
            InfoRow(Icons.Rounded.Info, "开源许可", "GPL‑3.0 · 版本 $versionName")
        }
    }

    when (dialog) {
        "name" -> NameDialog(settings.deviceName, { dialog = null }) { viewModel.setDeviceName(it); dialog = null }
        "mode" -> ChoiceDialog("默认模式", listOf(AppMode.SENDER to "发送端（投屏到别处）", AppMode.RECEIVER to "接收端（把本机当作屏幕）"),
            settings.mode ?: if (isTv) AppMode.RECEIVER else AppMode.SENDER, { dialog = null }) { viewModel.setMode(it); dialog = null }
        "quality" -> ChoiceDialog("画质档位", QualityProfile.entries.map { it to "${it.label}（${it.summary}）" }, settings.quality, { dialog = null }) {
            viewModel.setQuality(it); dialog = null
        }
        "policy" -> ChoiceDialog(
            "连接策略",
            listOf(ConnectionPolicy.TRUSTED_SKIP_CONFIRM to "已信任的设备免确认", ConnectionPolicy.ALWAYS_CONFIRM to "每次都确认"),
            settings.connectionPolicy, { dialog = null },
        ) { viewModel.setPolicy(it); dialog = null }
        "apple" -> AppleCompatDialog(settings.appleCompatPin, { dialog = null }) { pin ->
            viewModel.setAppleCompat(true, pin)
            dialog = null
        }
        "boot" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("开启开机自启？") },
            text = { Text("开机后会在后台等待投屏，略微增加耗电。Android 15 起系统限制开机时启动播放类前台服务，所以开机后只启动发现服务；部分电视系统会阻止后台自启。") },
            confirmButton = { TextButton(onClick = { viewModel.setBootStart(true); dialog = null }) { Text("开启") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NavRow(title: String, detail: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.tertiary)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备名") },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, singleLine = true, supportingText = { Text("显示在其他设备的列表里") })
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(value == selected, role = Role.RadioButton) { onSelect(value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 交互稿 09B：开启“兼容 Apple 设备”确认。全程不出现商标字样；风险说明为列表；PIN 默认开启（Q7）。 */
@Composable
private fun AppleCompatDialog(pinDefault: Boolean, onDismiss: () -> Unit, onEnable: (Boolean) -> Unit) {
    var pin by remember { mutableStateOf(pinDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Devices, null) },
        title = { Text("开启兼容 Apple 设备？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开启后，本机会出现在 iPhone、iPad 的“屏幕镜像”列表中。")
                Text("· 这是非官方的兼容实现，与 Apple Inc. 无关，未经其授权或认证\n· iOS 更新后可能暂时无法使用\n· 不支持受版权保护（DRM）的视频", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().toggleable(pin, role = Role.Checkbox) { pin = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(pin, onCheckedChange = null)
                    Column {
                        Text("连接时要求输入 PIN")
                        Text("推荐，防止他人误连", style = MaterialTheme.typography.bodySmall, color = WscTheme.extended.success)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onEnable(pin) }) { Text("开启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 可信设备列表（决策 #13：可以在列表里移除某台设备）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedDevicesScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val list by viewModel.trusted.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopAppBar(title = { Text("可信设备") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } })
        if (list.isEmpty()) {
            Text("还没有配对过的设备。第一次连接时输入 PIN、投屏码或扫码后会出现在这里。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            list.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text("证书指纹 ${d.fingerprint.shortDisplay} · 上次 ${java.text.DateFormat.getDateInstance().format(java.util.Date(d.lastSeenAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { confirm = d.deviceId }) { Text("移除") }
                }
                HorizontalDivider()
            }
        }
    }
    confirm?.let { id ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("移除这台设备？") },
            text = { Text("移除后，下次连接需要重新输入 PIN。") },
            confirmButton = { TextButton(onClick = { viewModel.removeTrusted(id); confirm = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}
