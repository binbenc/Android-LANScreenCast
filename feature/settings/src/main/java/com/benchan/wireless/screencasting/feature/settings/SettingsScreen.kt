package com.benchan.wireless.screencasting.feature.settings

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.benchan.wireless.screencasting.core.data.settings.effectiveMode
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.benchan.wireless.screencasting.core.ui.components.LocalModalOverlayVisible
import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.ui.components.tvInitialFocus
import com.benchan.wireless.screencasting.core.ui.components.tvSafeArea
import com.benchan.wireless.screencasting.core.ui.util.openAppDetailsSettings
import kotlinx.coroutines.launch
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
enum class SettingsDestination { TRUSTED, REPORT, DIAGNOSTICS, LOGS, INSTALL_HELP, REMOTE_CONTROL, ELDER }

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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    // TV：记住最后点进的行，从子页面返回时焦点回到该行（U6）
    var lastRow by rememberSaveable { mutableStateOf("name") }
    fun rowFocus(key: String): Modifier = if (key == lastRow) Modifier.tvInitialFocus() else Modifier
    val receiverMode = settings.effectiveMode(isTv) == AppMode.RECEIVER
    // 从系统设置返回时重新检查录音权限（N7）
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val hasMic = remember(resumeTick) { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    // 根部弹层（投屏确认 / Apple PIN）出现时收起本页的对话框，避免两个模态叠在一起；
    // 弹层关闭后 tvInitialFocus 会把焦点还给记住的最后一行（S-a）
    val modalOverlay = LocalModalOverlayVisible.current
    LaunchedEffect(modalOverlay) { if (modalOverlay) dialog = null }
    LaunchedEffect(openAppleCompat) {
        if (openAppleCompat && viewModel.appleCompatAvailable && !settings.appleCompatEnabled) dialog = "apple"
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    // 打开“声音”时申请录音权限（与投屏前的申请一致）；拒绝则保持关闭（A2）
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.setAudio(granted) }
    // 开机自启要靠通知把确认界面拉到前台：开启时申请通知权限（Android 13+），拒绝也照常开启（B2）
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.setBootStart(true) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    Column(Modifier.fillMaxSize().navigationBarsPadding().tvSafeArea()) {
        SettingsTopBar("设置", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group("本机")
            // TV：进入页面第一项获得焦点（B7）
            NavRow("设备名", settings.deviceName, modifier = rowFocus("name")) { lastRow = "name"; dialog = "name" }
            NavRow("默认模式", if (!receiverMode) "发送端（投屏到别处）" else "接收端（把本机当作屏幕）", modifier = rowFocus("mode")) { lastRow = "mode"; dialog = "mode" }
            // 可信设备对发送端和接收端都有效（双方互相信任），放在“本机”分组，不受“切换为接收端后生效”说明覆盖（R3）
            NavRow("可信设备", "${trusted.size} 台 · 可逐个移除", rowFocus("trusted"), icon = Icons.Rounded.Devices) { lastRow = "trusted"; onNavigate(SettingsDestination.TRUSTED) }
            // F24 长辈模式（交互稿 E02）：独立分组，只在发送端模式显示；导航行（开启前必须走向导）
            if (!isTv && !receiverMode) {
                Group("长辈模式")
                NavRow("长辈模式", "大字大按钮，一键投到家里的电视。由家人先设置好 · " + if (settings.elderModeEnabled) "已开启" else "关闭") {
                    lastRow = "elder"; onNavigate(SettingsDestination.ELDER)
                }
            }

            val sender = @Composable {
                Group("投屏")
                // 游戏档不切档（F18，UX 走查 P1-7）：两处文案按默认档位取
                NavRow("画质档位", qualityRowDetail(settings.quality, settings.autoAdjustQuality)) { dialog = "quality" }
                SwitchRow("自动调节", autoAdjustDetail(settings.quality), settings.autoAdjustQuality, viewModel::setAutoAdjust)
                // 声音、说明卡只对手机发送端有意义：TV 上隐藏（UX#20）
                if (!isTv) {
                    SwitchRow("声音", "只捕获手机播放的声音，不录麦克风", settings.audioEnabled, onChange = { on ->
                        if (on && !has(Manifest.permission.RECORD_AUDIO)) audioLauncher.launch(Manifest.permission.RECORD_AUDIO) else viewModel.setAudio(on)
                    })
                    if (settings.audioEnabled && !hasMic) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("未授予录音权限，投屏时只投画面", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { openAppDetailsSettings(context) }) { Text("去系统设置") }
                        }
                    }
                    SwitchRow("录屏前显示说明卡", "关闭后点设备直接弹出系统录屏授权", !settings.skipCaptureIntro, onChange = { viewModel.setSkipIntro(!it) })
                    // F14 Direct Share（决策 #34，交互稿 S08 / 09A）：关闭后立即移除全部快捷方式
                    SwitchRow("在分享菜单显示最近推送的电视", "分享照片和视频时，可以直接点电视的名字", settings.directShareEnabled, viewModel::setDirectShare)
                    // 反向控制（PRD F23，交互稿 09A / R01）：导航行 + 状态值（UX 走查 G12），点按进入 R01 再开关。
                    // B 期起 TV 接收端有控制入口：说明为“电视或平板”（用户决策 D3）
                    val rcServiceOn = remember(resumeTick, settings.remoteControlEnabled) { viewModel.remoteControlServiceOn() }
                    val rcValue = when {
                        !settings.remoteControlEnabled -> "已关闭"
                        rcServiceOn -> "已开启"
                        else -> "已开启 · 还需开启无障碍服务"
                    }
                    val rcWarn = settings.remoteControlEnabled && !rcServiceOn
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { lastRow = "remote"; onNavigate(SettingsDestination.REMOTE_CONTROL) }
                            .semantics(mergeDescendants = true) { stateDescription = rcValue }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("反向控制", style = MaterialTheme.typography.bodyLarge)
                            Text("允许电视、平板或手机在投屏时操作本机，每次都需你确认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                rcValue,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rcWarn) com.benchan.wireless.screencasting.core.ui.theme.WscTheme.extended.warning else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val receiver = @Composable {
                Group("接收（本机作为屏幕时）")
                // 发送端模式下接收端设置不立即生效：如实说明（与 TV 隐藏发送端项对称，P2）
                if (!receiverMode) {
                    Text(
                        "当前为发送端模式，以下设置在切换为接收端后生效",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NavRow("连接策略", if (settings.connectionPolicy == ConnectionPolicy.TRUSTED_SKIP_CONFIRM) "已信任的设备免确认" else "每次都确认", modifier = rowFocus("policy")) { lastRow = "policy"; dialog = "policy" }
                SwitchRow("DLNA 接收", "视频 App 的“投屏”按钮可以选择本机", settings.dlnaEnabled, viewModel::setDlna)
                SwitchRow("陌生设备需确认", "防止邻居或酒店住客误投", settings.dlnaConfirmUnknown, viewModel::setDlnaConfirm, enabled = settings.dlnaEnabled)
                if (viewModel.appleCompatAvailable) {
                    SwitchRow(
                        "兼容 Apple 设备（非官方）",
                        "开启后 iPhone、iPad 可用系统“屏幕镜像”连接本机",
                        settings.appleCompatEnabled,
                        { on -> if (on) dialog = "apple" else viewModel.setAppleCompat(false, settings.appleCompatPin) },
                    )
                    // 开启后可以单独修改“连接时要求 PIN”（D5）
                    if (settings.appleCompatEnabled) {
                        SwitchRow("连接时要求输入 PIN", "推荐开启，防止他人误连", settings.appleCompatPin, onChange = { viewModel.setAppleCompat(true, it) })
                    }
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
                // 开机自启只在接收端模式下有意义：发送端模式隐藏（BootReceiver 同样会检查模式，P2）
                if (receiverMode) {
                    SwitchRow("开机自启", "开机后在后台等待投屏（通知栏会常驻一条“正在后台等待投屏”），有人投屏时用通知打开确认界面；部分电视系统不支持后台唤起，此时需先打开本应用", settings.bootStart, onChange = { on ->
                        if (on) dialog = "boot" else viewModel.setBootStart(false)
                    })
                }
            }
            // TV 上隐藏整个“投屏”分组（画质、自动调节、声音、说明卡都只对手机发送端有意义，S3）
            if (isTv) receiver() else { sender(); receiver() }

            Group("诊断")
            NavRow(
                "投屏报告",
                summary.successRatePercent?.let { "最近 30 天成功率 $it% · 平均延迟 ${summary.avgLatencyMs ?: "—"} ms" } ?: "还没有投屏记录",
                rowFocus("report"),
                icon = Icons.Rounded.BarChart,
            ) { lastRow = "report"; onNavigate(SettingsDestination.REPORT) }
            NavRow("网络诊断", "检查网段、组播与路由器设置", rowFocus("diag"), icon = Icons.Rounded.Router) { lastRow = "diag"; onNavigate(SettingsDestination.DIAGNOSTICS) }
            NavRow("导出本地日志", "导出前可以先查看内容，用于提交 issue", rowFocus("logs"), icon = Icons.Rounded.IosShare) { lastRow = "logs"; onNavigate(SettingsDestination.LOGS) }
            if (!isTv) {
                NavRow("把接收端安装到电视", "手机在局域网内临时提供安装包", rowFocus("install"), icon = Icons.Rounded.InstallMobile) { lastRow = "install"; onNavigate(SettingsDestination.INSTALL_HELP) }
            }

            Group("关于")
            InfoRow(Icons.Rounded.PrivacyTip, "隐私", "本应用不访问互联网，不上报任何数据；屏幕镜像始终加密")
            InfoRow(Icons.Rounded.Info, "开源许可", "GPL‑3.0 · 版本 $versionName")
        }
    }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().tvSafeArea())
    }

    when (dialog) {
        "name" -> NameDialog(settings.deviceName, { dialog = null }) { viewModel.setDeviceName(it); dialog = null }
        "mode" -> ChoiceDialog("默认模式", listOf(AppMode.SENDER to "发送端（投屏到别处）", AppMode.RECEIVER to "接收端（把本机当作屏幕）"),
            settings.mode ?: if (isTv) AppMode.RECEIVER else AppMode.SENDER, { dialog = null }) { viewModel.setMode(it); dialog = null }
        "quality" -> ChoiceDialog("画质档位", QualityProfile.entries.map { it to qualityOption(it) }, settings.quality, { dialog = null }) {
            // F18 G06：本机还没确认过 G01 时，先弹首次说明卡，“开启”后才保存
            if (it == QualityProfile.GAME && !viewModel.gameIntroAcknowledged()) dialog = "game_intro" else viewModel.setQuality(it).also { dialog = null }
        }
        "game_intro" -> com.benchan.wireless.screencasting.core.ui.components.GameIntroDialog { enable ->
            if (enable) viewModel.acknowledgeGameIntroAndSetGame()
            dialog = null
        }
        "policy" -> ChoiceDialog(
            "连接策略",
            listOf(ConnectionPolicy.TRUSTED_SKIP_CONFIRM to "已信任的设备免确认", ConnectionPolicy.ALWAYS_CONFIRM to "每次都确认"),
            settings.connectionPolicy, { dialog = null },
        ) { viewModel.setPolicy(it); dialog = null }
        "apple" -> AppleCompatDialog(settings.appleCompatPin, { dialog = null }) { pin ->
            viewModel.setAppleCompat(true, pin)
            dialog = null
            // 开启后给出可感知的结果（D5；文案不出现商标字样）
            scope.launch { snackbar.showSnackbar("已开启。回到“等待投屏”页面后，iPhone、iPad 可以在“屏幕镜像”中找到本机", duration = SnackbarDuration.Long) }
        }
        "boot" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("开启开机自启？") },
            text = {
                Text(
                    "开机后会在后台等待投屏，略微增加耗电。系统要求后台运行的应用显示一条常驻通知“正在后台等待投屏”，可在通知上点“停止”。\n\n" +
                        "有人投屏时，本应用会用通知打开确认 / 播放界面，所以需要允许通知。" +
                        "部分电视系统不显示通知、或会阻止应用在后台自启，这种情况下仍需先打开本应用才能接收投屏。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setBootStart(true)
                    }
                }, modifier = Modifier.tvFocusRing()) { Text("开启") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }, modifier = Modifier.tvInitialFocus().tvFocusRing()) { Text("取消") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NavRow(title: String, detail: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    // clickable 的 Indication 在 TV 上由主题替换为明显的焦点样式（TvFocusIndication，B7）
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
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
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.tvFocusRing()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvFocusRing()) { Text("取消") } },
    )
}

private fun qualityRowDetail(p: QualityProfile, auto: Boolean): String = when {
    p.lowLatency -> "${p.label} · 720p · 60 帧 · 延迟优先"
    auto -> "${p.label} · 网络变差时自动降档"
    else -> p.label
}

private fun autoAdjustDetail(p: QualityProfile): String =
    if (p.lowLatency) "游戏模式下只降码率和清晰度，不切档" else "网络变差时自动降为“流畅”"

/** 默认画质选项：每项一句用途说明，“游戏”的说明含代价（交互稿 G06）。 */
private fun qualityOption(p: QualityProfile): String = when (p) {
    QualityProfile.GAME -> "${p.label}（720p · 60 帧 · 延迟优先，声音可能稍晚）"
    else -> "${p.label}（${p.summary}）"
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
                        (if (value == selected) Modifier.tvInitialFocus() else Modifier)
                            .fillMaxWidth().heightIn(min = 48.dp).selectable(value == selected, role = Role.RadioButton) { onSelect(value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvFocusRing()) { Text("关闭") } },
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
                        // 推荐说明不是“成功”状态：用 onSurfaceVariant（D2）
                        Text("推荐，防止他人误连", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onEnable(pin) }, modifier = Modifier.tvFocusRing()) { Text("开启") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvInitialFocus().tvFocusRing()) { Text("取消") } },
    )
}

/** 可信设备列表（决策 #13：可以在列表里移除某台设备）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedDevicesScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val list by viewModel.trusted.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().tvSafeArea()) {
        SettingsTopBar("可信设备", onBack, initialFocus = list.isEmpty())
        val pushed by viewModel.pushedTvs.collectAsStateWithLifecycle()
        var forgetTv by remember { mutableStateOf<String?>(null) }
        // 两组都空才显示空态，文案覆盖两类（第 1 轮 UX P1-10）
        if (list.isEmpty() && pushed.isEmpty()) {
            Text("还没有配对或推送过的设备。第一次镜像时输入 PIN、投屏码或扫码，或第一次推送到电视后会出现在这里。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (list.isNotEmpty() && pushed.isNotEmpty()) {
                Text("已配对的设备", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            list.forEachIndexed { i, d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        // 不用“证书指纹”这类术语；短识别码作为次要信息，便于和对方设备核对（C5）
                        Text("上次连接 ${java.text.DateFormat.getDateInstance().format(java.util.Date(d.lastSeenAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("设备识别码 ${d.fingerprint.shortDisplay}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { confirm = d.deviceId },
                        modifier = (if (i == 0) Modifier.tvInitialFocus() else Modifier).tvFocusRing(),
                    ) { Text("移除") }
                }
                HorizontalDivider()
            }
            // 推送过的电视（交互稿 P02 / Q14-B）：放在“已配对的设备”之后；忘记后下次推送重新出现确认卡
            if (pushed.isNotEmpty()) {
                Text("推送过的电视", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                pushed.forEach { tv ->
                    val model = listOfNotNull(tv.manufacturer, tv.model).joinToString(" · ")
                    val last = if (tv.lastUsedAt > 0) "上次推送 ${relativeAgo(tv.lastUsedAt)}" else "已确认，尚未推送"
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(tv.name, style = MaterialTheme.typography.bodyLarge)
                            if (model.isNotBlank()) Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(last, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { forgetTv = tv.udn },
                            modifier = Modifier.tvFocusRing().semantics { contentDescription = "忘记 ${tv.name}" },
                        ) { Text("忘记") }
                    }
                    HorizontalDivider()
                }
            }
        }
        forgetTv?.let { udn ->
            AlertDialog(
                onDismissRequest = { forgetTv = null },
                title = { Text("忘记这台电视？") },
                text = { Text("下次推送到这台电视时，会再次请你确认是不是面前的这台。") },
                confirmButton = { TextButton(onClick = { viewModel.forgetPushedTv(udn); forgetTv = null }, modifier = Modifier.tvFocusRing()) { Text("忘记") } },
                dismissButton = { TextButton(onClick = { forgetTv = null }, modifier = Modifier.tvInitialFocus().tvFocusRing()) { Text("取消") } },
            )
        }
    }
    confirm?.let { id ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("移除这台设备？") },
            text = { Text("移除后，下次连接需要重新输入 PIN。") },
            confirmButton = { TextButton(onClick = { viewModel.removeTrusted(id); confirm = null }, modifier = Modifier.tvFocusRing()) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirm = null }, modifier = Modifier.tvInitialFocus().tvFocusRing()) { Text("取消") } },
        )
    }
}

/** “刚刚 / n 分钟前 / n 小时前 / 昨天 / n 天前”。 */
internal fun relativeAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - at).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "刚刚"
        d < 3_600 -> "${d / 60} 分钟前"
        d < 86_400 -> "${d / 3_600} 小时前"
        d < 172_800 -> "昨天"
        else -> "${d / 86_400} 天前"
    }
}
