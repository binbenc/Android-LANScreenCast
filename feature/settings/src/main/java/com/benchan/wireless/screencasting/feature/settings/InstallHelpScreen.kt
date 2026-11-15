package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.feature.settings.apkshare.ApkSharePageText
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.ui.components.tvInitialFocus
import com.benchan.wireless.screencasting.core.ui.components.tvSafeArea
import com.benchan.wireless.screencasting.core.common.WscLog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.ui.components.QrCode
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.feature.settings.apkshare.ApkInfo
import com.benchan.wireless.screencasting.feature.settings.apkshare.ApkShareServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * F26：把接收端安装到电视（决策 #20）。手机临时起局域网 HTTP 服务分发本机 APK，电视用浏览器输入短网址下载；
 * 也可以用 U 盘。离开本页或 10 分钟后自动停止分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallHelpScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lan by viewModel.lan.collectAsStateWithLifecycle()
    val apk = remember { installedApk(context) }
    var server by remember { mutableStateOf<ApkShareServer?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var downloads by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { server?.stop() } }
    LaunchedEffect(server) {
        val s = server ?: return@LaunchedEffect
        val until = System.currentTimeMillis() + SHARE_WINDOW_MS
        while (System.currentTimeMillis() < until) {
            downloads = s.downloadCount
            delay(1_000)
        }
        s.stop()
        server = null
        url = null
    }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().navigationBarsPadding().tvSafeArea()) {
        SettingsTopBar(stringResource(R.string.settings_install_receiver), onBack, initialFocus = apk == null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings_install_intro), style = MaterialTheme.typography.bodyMedium)
            if (apk == null) {
                // 从应用商店以拆分安装包（App Bundle）安装时，本机没有可以独立安装的完整安装包（D7）
                Row(
                    Modifier.fillMaxWidth().background(WscTheme.extended.warningContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = WscTheme.extended.onWarningContainer)
                    Text(
                        stringResource(R.string.settings_install_split_warning),
                        style = MaterialTheme.typography.bodySmall, color = WscTheme.extended.onWarningContainer,
                    )
                }
            }
            if (apk != null && !apk.supports32Bit) {
                Row(
                    Modifier.fillMaxWidth().background(WscTheme.extended.warningContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = WscTheme.extended.onWarningContainer)
                    Text(
                        stringResource(R.string.settings_install_abi_warning, apk.abis.joinToString()),
                        style = MaterialTheme.typography.bodySmall, color = WscTheme.extended.onWarningContainer,
                    )
                }
            }
            val currentUrl = url
            if (currentUrl == null) {
                Text(stringResource(R.string.settings_install_method_share), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_install_method_share_detail), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                val pageText = apkPageText(context)
                val startFailed = stringResource(R.string.settings_install_start_failed)
                Button(
                    enabled = apk != null && lan != null,
                    onClick = {
                        val a = apk ?: return@Button
                        scope.launch {
                            val s = ApkShareServer(a, context.applicationInfo.loadLabel(context.packageManager).toString(), text = pageText)
                            runCatching { s.start() }.onSuccess { port ->
                                server = s
                                url = "http://${lan?.ip}:$port/${s.token}"
                                error = null
                            }.onFailure {
                                // 技术细节只写日志（C5）
                                WscLog.w("InstallHelp", "start apk share failed", it)
                                error = startFailed
                            }
                        }
                    },
                    modifier = Modifier.tvInitialFocus(apk != null).tvFocusRing().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_install_start)) }
                if (lan == null) Text(stringResource(R.string.settings_install_need_wifi), color = c.error)
                error?.let { Text(it, color = c.error) }
            } else {
                Text(stringResource(R.string.settings_install_enter_url), style = MaterialTheme.typography.titleMedium)
                Text(currentUrl, fontFamily = FontFamily.Monospace, fontSize = 22.sp, color = c.primary)
                QrCode(currentUrl, Modifier.size(180.dp), description = stringResource(R.string.settings_install_qr_desc))
                Text(pluralStringResource(R.plurals.settings_install_downloads, downloads, downloads), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                OutlinedButton(onClick = { server?.stop(); server = null; url = null }, modifier = Modifier.tvFocusRing()) { Text(stringResource(R.string.settings_install_stop)) }
            }
            Text(stringResource(R.string.settings_install_method_usb), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_install_method_usb_detail), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_install_fallback_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_install_fallback_detail), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}

private const val SHARE_WINDOW_MS = 10 * 60 * 1000L

/** 本机安装包。从应用商店以 App Bundle 安装（有 split APK）时，单个 base.apk 不能独立安装，返回 null。 */
private fun installedApk(context: Context): ApkInfo? {
    val info = context.applicationInfo
    if (!info.splitSourceDirs.isNullOrEmpty()) return null
    return ApkInfo.inspect(File(info.sourceDir))
}

/** 电视浏览器里下载页的文案：跟随手机当前语言（en / zh）。 */
private fun apkPageText(context: Context): ApkSharePageText = ApkSharePageText(
    lang = context.getString(R.string.settings_apk_page_lang),
    titleFormat = context.getString(R.string.settings_apk_page_title),
    intro = context.getString(R.string.settings_apk_page_intro),
    downloadFormat = context.getString(R.string.settings_apk_page_download),
    help = context.getString(R.string.settings_apk_page_help),
)
