package com.benchan.wireless.screencasting

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
import com.benchan.wireless.screencasting.feature.receiver.ui.ReceiverScreen
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
import javax.inject.Inject

/**
 * 单 APK 双模式入口（决策 #5）：手机 / 平板默认发送端，TV 默认接收端（TV 直接进入待机页，跳过引导）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var quickCast by mutableStateOf(false)
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val isTv = DeviceType.isTv(this)
        setContent {
            WscAppTheme(tv = isTv) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    AppNavHost(isTv)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(CastTileService.EXTRA_QUICK_CAST, false) == true) quickCast = true
        intent?.data?.takeIf { it.scheme == "wsc" }?.let { deepLink = it.toString() }
    }

    @Composable
    private fun AppNavHost(isTv: Boolean) {
        val settings by settingsRepository.settings.collectAsStateWithLifecycle(null)
        val s = settings ?: return
        val nav = rememberNavController()
        val scope = rememberCoroutineScope()
        val defaultMode = if (isTv) AppMode.RECEIVER else AppMode.SENDER
        val mode = s.mode ?: defaultMode
        val forceSender = quickCast || deepLink != null
        val start = if (!s.onboardingDone && !isTv && !forceSender) "onboarding" else "home"
        val version = BuildConfig.VERSION_NAME

        fun go(dest: SettingsDestination) = nav.navigate(
            when (dest) {
                SettingsDestination.TRUSTED -> "trusted"
                SettingsDestination.REPORT -> "report"
                SettingsDestination.DIAGNOSTICS -> "diagnostics"
                SettingsDestination.LOGS -> "logs"
                SettingsDestination.INSTALL_HELP -> "install"
            },
        )

        NavHost(nav, startDestination = start) {
            composable("onboarding") {
                OnboardingScreen(
                    defaultMode = defaultMode,
                    onStart = { m ->
                        scope.launch {
                            settingsRepository.setMode(m)
                            settingsRepository.setOnboardingDone()
                        }
                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
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
                        deepLink = deepLink,
                        onDeepLinkConsumed = {
                            deepLink = null
                            quickCast = false
                        },
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
        }
    }
}
