package com.benchan.wireless.screencasting

import com.benchan.wireless.screencasting.feature.control.ui.AccessibilityGuideScreen
import com.benchan.wireless.screencasting.feature.control.ui.RemoteControlSettingsScreen
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.benchan.wireless.screencasting.core.data.settings.AppMode
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.ui.theme.WscAppTheme
import com.benchan.wireless.screencasting.feature.receiver.ui.ReceiverModalHost
import com.benchan.wireless.screencasting.feature.receiver.ui.ReceiverScreen
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.feature.sender.tile.CastTileService
import com.benchan.wireless.screencasting.feature.sender.ui.SenderScreen
import com.benchan.wireless.screencasting.feature.settings.InstallHelpScreen
import com.benchan.wireless.screencasting.feature.settings.LogExportScreen
import com.benchan.wireless.screencasting.feature.settings.NetworkDiagnosticsScreen
import com.benchan.wireless.screencasting.feature.settings.ReportScreen
import com.benchan.wireless.screencasting.feature.settings.SettingsDestination
import com.benchan.wireless.screencasting.feature.settings.SettingsScreen
import com.benchan.wireless.screencasting.feature.settings.TrustedDevicesScreen
import com.benchan.wireless.screencasting.ui.OnboardingScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverController
import javax.inject.Inject

/**
 * 单 APK 双模式入口（决策 #5）：手机 / 平板默认发送端，TV 默认接收端（TV 直接进入待机页，跳过引导）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var senderController: SenderController

    @Inject
    lateinit var receiverController: ReceiverController

    /** 快捷设置图块请求（一次性事件，SenderScreen 处理后复位，B3）。 */
    private var quickCast by mutableStateOf(false)
    private var deepLink by mutableStateOf<String?>(null)

    /**
     * 图块 / 扫码深链接让“接收端模式”的设备临时显示发送端。离开 App 且没有进行中的投屏时恢复用户选择的模式，
     * 不再永久锁在发送端。
     */
    private var senderOverride by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 重建（进程回收后恢复）时不重复处理启动 intent，避免再次触发一键投屏
        if (savedInstanceState == null) handleIntent(intent)
        val isTv = DeviceType.isTv(this)
        setContent {
            WscAppTheme(tv = isTv) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    // 接收端确认弹窗 / Apple PIN 在 NavHost 之上渲染，设置页等任何页面都能确认（P1）
                    ReceiverModalHost { AppNavHost(isTv) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (senderController.state.value.isTerminal) senderOverride = false
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(CastTileService.EXTRA_QUICK_CAST, false) == true) {
            quickCast = true
            senderOverride = true
        }
        intent?.data?.takeIf { it.scheme == "wsc" }?.let {
            deepLink = it.toString()
            senderOverride = true
        }
    }

    @Composable
    private fun AppNavHost(isTv: Boolean) {
        val settings by settingsRepository.settings.collectAsStateWithLifecycle(null)
        val s = settings ?: return
        val nav = rememberNavController()
        val scope = rememberCoroutineScope()
        val defaultMode = if (isTv) AppMode.RECEIVER else AppMode.SENDER
        val mode = s.mode ?: defaultMode
        val forceSender = quickCast || deepLink != null || senderOverride
        // 起始页只在首次组合时决定：引导页里写入 onboardingDone 后若再改 startDestination，NavHost 会重建图、
        // 首页被销毁重建，接收端监听的引用计数随之抖动（E8）
        val start = remember { if (!s.onboardingDone && !isTv && !forceSender) "onboarding" else "home" }
        val version = BuildConfig.VERSION_NAME

        fun go(dest: SettingsDestination) = nav.navigate(
            when (dest) {
                SettingsDestination.TRUSTED -> "trusted"
                SettingsDestination.REPORT -> "report"
                SettingsDestination.DIAGNOSTICS -> "diagnostics"
                SettingsDestination.LOGS -> "logs"
                SettingsDestination.INSTALL_HELP -> "install"
                SettingsDestination.REMOTE_CONTROL -> "remote_control"
            },
        )

        // 在设置等页面里允许了投屏请求：回到首页（播放页），否则画面没有可挂的渲染器（P1）
        LaunchedEffect(nav) {
            receiverController.anyPlayback.collect { playing ->
                if (!playing) return@collect
                // 处于图块 / 深链接触发的临时发送端时，先恢复为接收端，首页才会显示播放页（R7）
                if (senderOverride && senderController.state.value.isTerminal) {
                    senderOverride = false
                    quickCast = false
                    deepLink = null
                }
                if (nav.currentDestination?.route != "home") nav.popBackStack("home", inclusive = false)
            }
        }

        NavHost(nav, startDestination = start) {
            composable("onboarding") {
                OnboardingScreen(
                    defaultMode = defaultMode,
                    onStart = { m ->
                        scope.launch {
                            settingsRepository.setMode(m)
                            settingsRepository.setOnboardingDone()
                            // 模式写入后再进首页：避免先按默认模式显示发送端、再切到接收端（E8）
                            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                        }
                    },
                    onInstallHelp = { nav.navigate("install") },
                )
            }
            composable("home") {
                if (mode == AppMode.SENDER || forceSender) {
                    SenderScreen(
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenDiagnostics = { nav.navigate("diagnostics") },
                        onOpenInstallHelp = { nav.navigate("install") },
                        quickCast = quickCast,
                        onQuickCastConsumed = { quickCast = false },
                        deepLink = deepLink,
                        onDeepLinkConsumed = { deepLink = null },
                    )
                } else {
                    ReceiverScreen(
                        onOpenSettings = { nav.navigate("settings") },
                        onEnableAppleCompat = { nav.navigate("settings?apple=true") },
                    )
                }
            }
            composable("settings?apple={apple}") { entry ->
                SettingsScreen(
                    isTv = isTv,
                    versionName = version,
                    onBack = { nav.popBackStack() },
                    onNavigate = ::go,
                    openAppleCompat = entry.arguments?.getString("apple") == "true",
                )
            }
            composable("trusted") { TrustedDevicesScreen(onBack = { nav.popBackStack() }) }
            composable("report") { ReportScreen(onBack = { nav.popBackStack() }, onExport = { nav.navigate("logs") }) }
            composable("diagnostics") { NetworkDiagnosticsScreen(onBack = { nav.popBackStack() }) }
            composable("logs") { LogExportScreen(versionName = version, onBack = { nav.popBackStack() }) }
            composable("install") { InstallHelpScreen(onBack = { nav.popBackStack() }) }
            // 反向控制（PRD F23）：R01 设置 → R02 引导；R02 检测到服务已开启时返回 R01 并提示
            composable("remote_control") { entry ->
                val justEnabled by entry.savedStateHandle.getStateFlow("rc_enabled", false).collectAsStateWithLifecycle()
                RemoteControlSettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenGuide = { nav.navigate("remote_control_guide") },
                    justEnabled = justEnabled,
                    onJustEnabledShown = { entry.savedStateHandle["rc_enabled"] = false },
                )
            }
            composable("remote_control_guide") {
                AccessibilityGuideScreen(
                    onBack = { nav.popBackStack() },
                    onEnabled = {
                        nav.previousBackStackEntry?.savedStateHandle?.set("rc_enabled", true)
                        nav.popBackStack()
                    },
                )
            }
        }
    }
}
