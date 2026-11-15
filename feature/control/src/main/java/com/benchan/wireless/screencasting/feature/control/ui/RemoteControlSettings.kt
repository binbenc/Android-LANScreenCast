package com.benchan.wireless.screencasting.feature.control.ui

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.core.ui.util.openAppDetailsSettings
import com.benchan.wireless.screencasting.feature.control.AccessibilityBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteControlSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val bridge: AccessibilityBridge,
) : ViewModel() {
    val enabled: StateFlow<Boolean?> = settings.settings.map { it.remoteControlEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val connected: StateFlow<Boolean> = bridge.connected

    fun serviceEnabled(): Boolean = bridge.isServiceEnabledInSettings()

    fun advancedProtection(): Boolean = bridge.advancedProtectionBlocks()

    /** 系统设置里服务已开启但应用内总开关关闭时，用户可直接关闭服务（PM 走查 G9）。 */
    fun disableService() = bridge.disableService()

    val autoDisable: StateFlow<Boolean> = settings.settings.map { it.remoteControlAutoDisable }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoDisabled: StateFlow<Boolean> = settings.settings.map { it.remoteControlAutoDisabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoDisable(on: Boolean) {
        viewModelScope.launch { settings.setRemoteControlAutoDisable(on) }
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { settings.setRemoteControlEnabled(on) }
        // 关闭总开关时让服务自行停用，不留空挂的无障碍服务（交互稿 R01）
        if (!on) bridge.disableService()
    }
}

/**
 * 交互稿 R01：设置 · 反向控制。入口：设置 09A“投屏”组“反向控制”行。
 *
 * @param justEnabled 从 R02 返回且服务已开启：显示 Snackbar“已开启，投屏时对方可以请求控制”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlSettingsScreen(
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    justEnabled: Boolean = false,
    onJustEnabledShown: () -> Unit = {},
    viewModel: RemoteControlSettingsViewModel = hiltViewModel(),
) {
    val enabledState by viewModel.enabled.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val enabled = enabledState ?: return
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val snackbar = remember { SnackbarHostState() }
    val resumeTick = rememberResumeTick()
    val serviceOn = remember(resumeTick, connected) { viewModel.serviceEnabled() }
    val aapm = remember(resumeTick) { viewModel.advancedProtection() }
    val context = LocalContext.current
    val notificationsOn = remember(resumeTick) { androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
    var notificationAsked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 开启时申请通知权限；拒绝也允许开启（用户决策 D6），此时被控制只能从屏幕边缘胶囊收回
    val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    LaunchedEffect(justEnabled) {
        if (justEnabled) {
            onJustEnabledShown()
            snackbar.showSnackbar("已开启，投屏时对方可以请求控制")
        }
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            TopAppBar(
                title = { Text("反向控制") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(enabled && !aapm, enabled = !aapm, role = Role.Switch) { on ->
                            viewModel.setEnabled(on)
                            if (on && Build.VERSION.SDK_INT >= 33 && !notificationsOn) {
                                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            // 首次打开开关且服务未开启 → 直接进入 R02
                            if (on && !serviceOn) onOpenGuide()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("允许对方操作本机", style = MaterialTheme.typography.bodyLarge, color = if (aapm) c.outline else c.onSurface)
                        Text(
                            if (aapm) {
                                "本机开启了高级保护模式，系统不允许本应用使用无障碍服务，反向控制不可用。投屏不受影响。"
                            } else {
                                "投屏时，电视、平板或手机可以请求点击、滑动等操作。每次都需要你在手机上确认"
                            },
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = enabled && !aapm, onCheckedChange = null, enabled = !aapm,
                        // 不可用态统一 outline / surfaceContainer（UX 走查 G3）
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            disabledUncheckedTrackColor = c.surfaceContainer, disabledUncheckedThumbColor = c.outline,
                            disabledUncheckedBorderColor = c.outline, disabledCheckedTrackColor = c.surfaceContainer, disabledCheckedThumbColor = c.outline,
                        ),
                    )
                }
                if (enabled && !aapm && !notificationsOn) {
                    // 交互稿 R01（D6）：surfaceContainer 中性底；“允许通知”先请求运行时权限，不可请求时打开系统通知设置
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                            .background(c.surfaceContainer, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.NotificationsOff, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                            Text("没有通知权限：被控制时只能从屏幕边缘胶囊收回", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                        }
                        TextButton(onClick = {
                            val activity = context as? android.app.Activity
                            val canAsk = Build.VERSION.SDK_INT >= 33 && activity != null &&
                                (!notificationAsked || activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))
                            if (canAsk) {
                                notificationAsked = true
                                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                com.benchan.wireless.screencasting.core.ui.util.openAppNotificationSettings(context)
                            }
                        }) { Text("允许通知") }
                    }
                }
                val autoDisabled by viewModel.autoDisabled.collectAsStateWithLifecycle()
                if (enabled && !aapm && !serviceOn && autoDisabled) {
                    // UX G7：上次投屏结束时已按设置自动关闭服务
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                            .background(c.surfaceContainer, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text("无障碍服务已按你的设置关闭", style = MaterialTheme.typography.labelLarge)
                        Text("下次被控制前再开启", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        TextButton(onClick = { openServiceDetails(context) }) { Text("去开启") }
                    }
                }
                if (!enabled && !aapm && serviceOn) {
                    // 系统设置里服务仍开着、应用内已关闭：服务不会做任何事，给出一键关闭（PM 走查 G9）
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                        Text("系统设置中本应用的无障碍服务仍是开启状态；反向控制关闭时它不会做任何事", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        TextButton(onClick = viewModel::disableService) { Text("关闭服务") }
                    }
                }
                if (enabled && !aapm) {
                    if (serviceOn) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = ext.success)
                            Text("无障碍服务已开启", style = MaterialTheme.typography.bodyMedium, color = ext.success)
                        }
                    } else if (!autoDisabled) {
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).fillMaxWidth()
                                .background(ext.warningContainer, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Accessibility, null, Modifier.size(18.dp), tint = ext.onWarningContainer)
                                Text("还差一步：开启无障碍服务", style = MaterialTheme.typography.labelLarge, color = ext.onWarningContainer)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("系统只允许通过无障碍服务模拟点击和输入。本应用不读取屏幕内容，只识别当前打开的是哪个应用、哪个页面，用于在一些界面（例如安装应用、授予权限）自动暂停控制。", style = MaterialTheme.typography.bodySmall, color = ext.onWarningContainer)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenGuide) { Text("去开启") }
                        }
                    }
                }
                // 能力行 = 当前构建已实现 × 本机系统版本（D2）；已实现但本机版本不够的项用 outline 色说明原因，不隐藏
                GroupLabel("本机支持的操作")
                // 支持的行不写副文案；不支持的行用 outline 写原因（UX 走查 G15）
                CapabilityRow("点击、长按、滑动、滚动", null, true)
                CapabilityRow("返回、主页、最近任务", null, true)
                CapabilityRow("双指缩放", null, true)
                val api33 = Build.VERSION.SDK_INT >= 33
                val api36 = Build.VERSION.SDK_INT >= 36
                CapabilityRow("文字输入、遥控器方向键", if (api33) null else "需要 Android 13 及以上", api33)
                CapabilityRow("播放 / 暂停键", if (api36) null else "需要 Android 16 及以上", api36)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = c.outlineVariant)
                GroupLabel("安全")
                // C 期：投屏结束后自动关闭无障碍服务（默认关）。与下方安全行同一对齐方式：前置图标 + 文字列（UX G6）
                val autoDisable by viewModel.autoDisable.collectAsStateWithLifecycle()
                val switchOk = enabled && !aapm
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(autoDisable, enabled = switchOk, role = Role.Switch) { viewModel.setAutoDisable(it) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Rounded.Accessibility, null, tint = if (switchOk) c.tertiary else c.outline)
                    Column(Modifier.weight(1f)) {
                        Text("投屏结束后自动关闭无障碍服务", style = MaterialTheme.typography.bodyLarge, color = if (switchOk) c.onSurface else c.outline)
                        Text("不投屏时不保留操作本机的权限。每次被控制前需重新到系统设置开启", style = MaterialTheme.typography.bodySmall, color = if (switchOk) c.onSurfaceVariant else c.outline)
                    }
                    Switch(
                        checked = autoDisable, onCheckedChange = null, enabled = switchOk,
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            disabledUncheckedTrackColor = c.surfaceContainer, disabledUncheckedThumbColor = c.outline,
                            disabledUncheckedBorderColor = c.outline, disabledCheckedTrackColor = c.surfaceContainer, disabledCheckedThumbColor = c.outline,
                            disabledCheckedBorderColor = c.outline,
                        ),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Shield, null, tint = c.tertiary)
                    Column {
                        Text("每次投屏都要你确认", style = MaterialTheme.typography.bodyLarge)
                        Text("已信任的设备也不例外；投屏结束、锁屏或熄屏后立即失效", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Lock, null, tint = c.tertiary)
                    Column {
                        Text("本应用不能被远程操作", style = MaterialTheme.typography.bodyLarge)
                        Text("本应用自己的界面不能被远程操作；你在本应用内时控制会自动暂停", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                // C 期敏感界面（PM G3）；如实说明识别范围（D-2 ③）
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Shield, null, tint = c.tertiary)
                    Column {
                        Text("安装应用、授予权限等界面只能由你本人操作", style = MaterialTheme.typography.bodyLarge)
                        Text("控制会在这类界面尽量自动暂停", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun GroupLabel(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** 能力行：按本机系统版本实时显示；不支持的项用 outline 色，不隐藏（交互稿 R01）。 */
@Composable
private fun CapabilityRow(title: String, detail: String?, supported: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(if (supported) Icons.Rounded.Check else Icons.Rounded.Info, null, Modifier.size(20.dp), tint = if (supported) WscTheme.extended.success else c.outline)
        Column(Modifier.semantics(mergeDescendants = true) { if (!supported) stateDescription = "本机不支持" }) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (supported) c.onSurface else c.outline)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (supported) c.onSurfaceVariant else c.outline) }
        }
    }
}

/**
 * 交互稿 R02：引导 · 开启无障碍服务。回到本页时自动检测：已开启 → [onEnabled]；未开启 → 停留，不打扰。
 * “暂不开启” = 总开关回到关。
 */
@Composable
fun AccessibilityGuideScreen(
    onBack: () -> Unit,
    onEnabled: () -> Unit,
    viewModel: RemoteControlSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val resumeTick = rememberResumeTick()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(resumeTick, connected) {
        if (!done && viewModel.serviceEnabled()) {
            done = true
            onEnabled()
        }
    }
    val showRestricted = remember { Build.VERSION.SDK_INT >= 33 && isSideloaded(context) }
    Column(Modifier.fillMaxSize().background(c.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "关闭") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            IconTile(rememberVectorPainter(Icons.Rounded.Accessibility))
            Spacer(Modifier.height(16.dp))
            Text("开启无障碍服务", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("反向控制需要它来模拟点击和输入。", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // 披露卡（交互稿 v1.0.4，用户决策 D-1）：放在步骤之前，知情在前、动作在后；读屏合并为一个节点
            Row(
                Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(16.dp)).padding(14.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(20.dp), tint = c.tertiary)
                Column {
                    Text("本应用不读取屏幕内容", style = MaterialTheme.typography.labelLarge, color = c.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(com.benchan.wireless.screencasting.feature.control.ControlCopy.PRIVACY_SHORT, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(1, "点「去系统设置」，进入「无障碍」")
                Step(2, "在「已下载的应用」中找到「局域投屏 · 反向控制」，打开开关")
                Step(3, "系统提示时选「允许」，再返回本应用")
                // G4：系统确认框里的“查看和控制屏幕”是通用说明
                Text(
                    "系统提示里的「查看和控制屏幕」是通用说明，本应用没有申请读取屏幕内容的权限",
                    Modifier.padding(start = 36.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
            }
            if (showRestricted) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("开关是灰色的，打不开？", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android 13 起，系统会限制非应用商店安装的应用。打开「应用信息」，点右上角 ⋮ →「允许受限制的设置」，再回到第 2 步。",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                    TextButton(onClick = { openAppDetailsSettings(context) }) { Text("打开应用信息") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("控制经常自己失效？", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text("部分手机会在后台关闭无障碍服务。请在系统设置里允许本应用「自启动」和「后台运行」（各品牌名称不同）。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { openAccessibilitySettings(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("去系统设置") }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                viewModel.setEnabled(false)
                onBack()
            }) { Text("暂不开启") }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(c.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = c.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** ON_RESUME 计数：从系统设置返回时重新检测服务状态。 */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return tick
}

/** Android 13+ 直接打开本服务的详情页，其他版本打开无障碍设置（PM G2）。 */
private fun openServiceDetails(context: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        val component = android.content.ComponentName(context, com.benchan.wireless.screencasting.feature.control.RemoteControlService::class.java)
        val ok = runCatching {
            context.startActivity(
                // 系统设置的“无障碍服务详情”页（SDK 未公开该常量，系统 Settings 自 Android 13 起响应此 Action；不可用时退回无障碍设置）
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (ok) return
    }
    openAccessibilitySettings(context)
}

private fun openAccessibilitySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { openAppDetailsSettings(context) }
}

/**
 * 是否非应用商店安装（浏览器 / 文件管理器 / 局域网分享安装包）：Android 13+ 这类安装会触发“受限制的设置”。
 * 无法精确判断系统是否真的限制了本应用，只用于决定是否显示提示卡。
 */
private fun isSideloaded(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
    return installer == null || installer in SIDELOAD_INSTALLERS
}

private val SIDELOAD_INSTALLERS = setOf(
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.miui.packageinstaller",
    "com.samsung.android.packageinstaller",
    "com.oplus.appdetail",
    "com.android.shell",
)
