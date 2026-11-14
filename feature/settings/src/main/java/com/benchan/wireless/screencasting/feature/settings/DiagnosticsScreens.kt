package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.core.ui.components.LocalIsTv
import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.ui.components.tvInitialFocus
import com.benchan.wireless.screencasting.core.ui.components.tvSafeArea
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.discovery.diagnostics.CheckStatus
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import kotlinx.coroutines.launch

/** F11 网络诊断页：能自动判断的打绿勾，只能由用户确认的打琥珀问号（交互稿 04 规则）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.runDiagnostics(null) }
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    Column(Modifier.fillMaxSize().navigationBarsPadding().tvSafeArea()) {
        // 默认焦点给返回按钮：“重新诊断”在诊断进行中不可用，不能作为初始焦点（U6）
        SettingsTopBar("网络诊断", onBack, initialFocus = true)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("不读取 Wi‑Fi 名称（需要定位权限），只检查网段与连通性。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            items(checks.orEmpty()) { ch ->
                val (icon, tint, label) = when (ch.status) {
                    CheckStatus.CONFIRMED -> Triple(Icons.Rounded.CheckCircle, ext.success, "已确认")
                    CheckStatus.NEEDS_USER -> Triple(Icons.Rounded.HelpOutline, ext.warning, "需你确认")
                    CheckStatus.PROBLEM -> Triple(Icons.Rounded.ErrorOutline, c.error, "有问题")
                }
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "$label：${ch.title}${if (ch.title.last() in "？?。") "" else "。"}${ch.detail}" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(icon, null, tint = tint)
                    Column {
                        Text(ch.title, style = MaterialTheme.typography.titleSmall)
                        Text(ch.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("测试能否连上电视", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        target, { target = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' } },
                        singleLine = true, label = { Text("电视地址，例如 192.168.1.128:8443") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.runDiagnostics(target) }, enabled = !running, modifier = Modifier.tvFocusRing()) { Text("重新诊断") }
                        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/** F25：导出本地日志——先预览全文，再由用户决定是否通过系统分享发出（PRD 14）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogExportScreen(versionName: String, onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = viewModel.buildLogPreview(versionName) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().tvSafeArea()) {
        SettingsTopBar("导出本地日志", onBack)
        Text(
            "以下是将要导出的全部内容，请先确认。导出后由你选择发给谁（例如在 GitHub 提 issue 时附上），本应用不会自动上传。",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (LocalIsTv.current) {
            // TV 上遥控器无法滚动查看长文本：只显示摘要，导出后在手机 / 电脑上查看全文（S10）
            Text(
                text?.let { "共 ${it.lines().size} 行（对端设备名已脱敏）。电视上不显示全文，导出后可在手机或电脑上查看。" } ?: "正在读取…",
                Modifier.weight(1f).padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(
                Modifier.weight(1f).padding(16.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(text ?: "正在读取…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.clearLog(); scope.launch { text = viewModel.buildLogPreview(versionName) } }, modifier = Modifier.tvFocusRing()) { Text("清空运行日志") }
            Button(
                enabled = text != null,
                modifier = Modifier.tvInitialFocus(text != null).tvFocusRing(),
                onClick = {
                    val content = text ?: return@Button
                    scope.launch {
                        val file = viewModel.writeExport(content)
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "导出诊断日志"))
                    }
                },
            ) { Text("导出") }
        }
    }
}
