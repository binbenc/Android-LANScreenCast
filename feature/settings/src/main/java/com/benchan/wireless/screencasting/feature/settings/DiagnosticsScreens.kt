package com.benchan.wireless.screencasting.feature.settings

import com.benchan.wireless.screencasting.core.discovery.diagnostics.DiagnosticCheck
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
        SettingsTopBar(stringResource(R.string.settings_network_diag), onBack, initialFocus = true)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.settings_diag_intro), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            items(checks.orEmpty()) { ch ->
                val (icon, tint, labelRes) = when (ch.status) {
                    CheckStatus.CONFIRMED -> Triple(Icons.Rounded.CheckCircle, ext.success, R.string.settings_diag_status_confirmed)
                    CheckStatus.NEEDS_USER -> Triple(Icons.Rounded.HelpOutline, ext.warning, R.string.settings_diag_status_needs_user)
                    CheckStatus.PROBLEM -> Triple(Icons.Rounded.ErrorOutline, c.error, R.string.settings_diag_status_problem)
                }
                val (title, detail) = diagnosticText(ch)
                val sentenceEnd = stringResource(R.string.settings_diag_sentence_end)
                val desc = stringResource(
                    R.string.settings_diag_item_desc,
                    stringResource(labelRes),
                    if (title.isNotEmpty() && title.last() in "？?。.") title else title + sentenceEnd,
                    detail,
                )
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = desc },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(icon, null, tint = tint)
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_diag_target_section), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        target, { target = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' } },
                        singleLine = true, label = { Text(stringResource(R.string.settings_diag_target_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.runDiagnostics(target) }, enabled = !running, modifier = Modifier.tvFocusRing()) { Text(stringResource(R.string.settings_diag_rerun)) }
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
        SettingsTopBar(stringResource(R.string.settings_export_logs), onBack)
        Text(
            stringResource(R.string.settings_logs_intro),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (LocalIsTv.current) {
            // TV 上遥控器无法滚动查看长文本：只显示摘要，导出后在手机 / 电脑上查看全文（S10）
            Text(
                text?.lines()?.size?.let { pluralStringResource(R.plurals.settings_logs_tv_summary, it, it) } ?: stringResource(R.string.settings_logs_loading),
                Modifier.weight(1f).padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(
                Modifier.weight(1f).padding(16.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(text ?: stringResource(R.string.settings_logs_loading), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        val chooserTitle = stringResource(R.string.settings_logs_chooser)
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.clearLog(); scope.launch { text = viewModel.buildLogPreview(versionName) } }, modifier = Modifier.tvFocusRing()) { Text(stringResource(R.string.settings_logs_clear)) }
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
                        context.startActivity(Intent.createChooser(send, chooserTitle))
                    }
                },
            ) { Text(stringResource(R.string.settings_logs_export)) }
        }
    }
}

/** 诊断项 → (标题, 说明)：core/discovery 只给结构化结论，文案在这里映射到字符串资源。 */
@Composable
private fun diagnosticText(ch: DiagnosticCheck): Pair<String, String> = when (ch) {
    DiagnosticCheck.NoLan -> stringResource(R.string.settings_diag_no_lan_title) to stringResource(R.string.settings_diag_no_lan_detail)
    is DiagnosticCheck.LanConnected -> stringResource(
        R.string.settings_diag_lan_title,
        stringResource(if (ch.ethernet) R.string.settings_diag_transport_ethernet else R.string.settings_diag_transport_wifi),
    ) to stringResource(R.string.settings_diag_lan_detail, ch.ip, ch.subnetHint)
    DiagnosticCheck.VpnActive -> stringResource(R.string.settings_diag_vpn_title) to stringResource(R.string.settings_diag_vpn_detail)
    is DiagnosticCheck.LargePrefix -> stringResource(R.string.settings_diag_prefix_title, ch.prefixLength) to stringResource(R.string.settings_diag_prefix_detail)
    is DiagnosticCheck.MulticastOk -> stringResource(R.string.settings_diag_multicast_ok_title) to
        pluralStringResource(R.plurals.settings_diag_multicast_ok_detail, ch.responders, ch.responders)
    DiagnosticCheck.MulticastSilent -> stringResource(R.string.settings_diag_multicast_silent_title) to stringResource(R.string.settings_diag_multicast_silent_detail)
    is DiagnosticCheck.TargetReachable -> stringResource(R.string.settings_diag_target_ok_title, ch.host) to stringResource(R.string.settings_diag_target_ok_detail, ch.port)
    is DiagnosticCheck.TargetUnreachable -> stringResource(R.string.settings_diag_target_fail_title, ch.host, ch.port) to stringResource(R.string.settings_diag_target_fail_detail)
    DiagnosticCheck.AskSameRouter -> stringResource(R.string.settings_diag_router_title) to stringResource(R.string.settings_diag_router_detail)
    DiagnosticCheck.AskApIsolation -> stringResource(R.string.settings_diag_isolation_title) to stringResource(R.string.settings_diag_isolation_detail)
    DiagnosticCheck.AskGuestNetwork -> stringResource(R.string.settings_diag_guest_title) to stringResource(R.string.settings_diag_guest_detail)
}
