package com.benchan.wireless.screencasting.feature.settings

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
        SettingsTopBar("把接收端安装到电视", onBack, initialFocus = apk == null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("同一个安装包既是发送端也是接收端。电视安装后打开，会自动进入“等待投屏”。", style = MaterialTheme.typography.bodyMedium)
            if (apk == null) {
                // 从应用商店以拆分安装包（App Bundle）安装时，本机没有可以独立安装的完整安装包（D7）
                Row(
                    Modifier.fillMaxWidth().background(WscTheme.extended.warningContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = WscTheme.extended.onWarningContainer)
                    Text(
                        "本机的应用是以拆分安装包形式安装的（例如来自应用商店），无法直接分享给电视。" +
                            "请从项目发布页下载 universal（通用）安装包，用 U 盘或电视浏览器安装。",
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
                        "本机安装的是只含 ${apk.abis.joinToString()} 的安装包，很多电视盒子是 32 位系统，可能装不上。" +
                            "可改用发布页上的 universal（通用）安装包，或用 U 盘安装对应版本。",
                        style = MaterialTheme.typography.bodySmall, color = WscTheme.extended.onWarningContainer,
                    )
                }
            }
            val currentUrl = url
            if (currentUrl == null) {
                Text("方法一：手机临时分享安装包（推荐）", style = MaterialTheme.typography.titleMedium)
                Text("手机和电视要连同一个路由器。分享只在局域网内可见，离开本页或 10 分钟后自动停止。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Button(
                    enabled = apk != null && lan != null,
                    onClick = {
                        val a = apk ?: return@Button
                        scope.launch {
                            val s = ApkShareServer(a, "局域投屏")
                            runCatching { s.start() }.onSuccess { port ->
                                server = s
                                url = "http://${lan?.ip}:$port/${s.token}"
                                error = null
                            }.onFailure {
                                // 技术细节只写日志（C5）
                                WscLog.w("InstallHelp", "start apk share failed", it)
                                error = "无法开启分享，请确认手机已连接 Wi‑Fi 后重试，或改用 U 盘安装"
                            }
                        }
                    },
                    modifier = Modifier.tvInitialFocus(apk != null).tvFocusRing().heightIn(min = 48.dp),
                ) { Text("开始分享") }
                if (lan == null) Text("请先连接 Wi‑Fi", color = c.error)
                error?.let { Text(it, color = c.error) }
            } else {
                Text("在电视的浏览器地址栏输入：", style = MaterialTheme.typography.titleMedium)
                Text(currentUrl, fontFamily = FontFamily.Monospace, fontSize = 22.sp, color = c.primary)
                QrCode(currentUrl, Modifier.size(180.dp), description = "安装包下载地址二维码（给其他手机或平板用）")
                Text("已下载 $downloads 次", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                OutlinedButton(onClick = { server?.stop(); server = null; url = null }, modifier = Modifier.tvFocusRing()) { Text("停止分享") }
            }
            Text("方法二：U 盘", style = MaterialTheme.typography.titleMedium)
            Text("把安装包拷到 U 盘，插到电视上，用电视的“文件管理”打开安装。部分电视需要先在系统设置里允许“安装未知来源应用”。", style = MaterialTheme.typography.bodyMedium)
            Text("电视实在装不了？", style = MaterialTheme.typography.titleMedium)
            Text("很多电视自带 DLNA 接收：在视频 App 里点“投屏”按钮可以直接推送到电视（本应用推送到电视自带接收端的功能在下个版本提供）。", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
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
