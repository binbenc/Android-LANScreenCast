package com.benchan.wireless.screencasting.feature.sender.ui.push

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.text.style.TextAlign
import com.benchan.wireless.screencasting.core.ui.components.WscButtonColors
import com.benchan.wireless.screencasting.feature.sender.ui.reportFooterHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.ui.theme.WscTheme
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushEndInfo
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.asString

/** P04 推送网址：不自动读剪贴板，只有点 [粘贴] 才读；网址只在本次会话内存里。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushUrlScreen(targetName: String, audioOnly: Boolean, initial: String, onBack: () -> Unit, onPush: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(PushCopy.urlKindHint(initial)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(text) {
        delay(500)
        hint = PushCopy.urlKindHint(text)
    }
    val valid = PushCopy.isPushableUrl(text)
    fun submit() {
        if (valid) onPush(text.trim()) else error = true
    }
    Column(Modifier.fillMaxSize().background(c.surface).imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.push_url_title, targetName)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.push_back)) } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                label = { Text(stringResource(if (audioOnly) R.string.push_url_label_audio else R.string.push_url_label)) },
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text(stringResource(R.string.push_url_error)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    clip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { text = it; error = false }
                }) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.push_paste))
                }
                Spacer(Modifier.weight(1f))
                Text(hint.asString(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(
                stringResource(if (audioOnly) R.string.push_url_note_audio else R.string.push_url_note),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text(stringResource(R.string.push_url_privacy), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().reportFooterHeight().padding(16.dp)) {
            Button(onClick = { submit() }, enabled = text.isNotBlank(), colors = WscButtonColors.filled(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.push_push)) }
        }
    }
}

/** P14 结束页：标题 = 结束原因；摘要；“已停止分享、链接已失效”。内容可滚动，按钮固定页脚（第 1 轮 UX P1-5）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushEndScreen(info: PushEndInfo, tvName: String, onAgain: () -> Unit, onResume: () -> Unit, onBack: () -> Unit, onDiagnostics: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val ext = WscTheme.extended
    val (titleText, detailText) = PushCopy.endTitle(info, tvName)
    val title = titleText.asString()
    val detail = detailText.asString()
    val warn = info.reason == PushEndReason.DEVICE_LOST || info.reason == PushEndReason.NETWORK_CHANGED
    val failed = info.failure != null
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(c.surface)) {
        TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.push_close)) } })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.size(56.dp).background(
                    when {
                        warn -> ext.warningContainer
                        failed -> c.errorContainer
                        else -> c.surfaceContainerHigh
                    },
                    RoundedCornerShape(16.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        failed -> Icons.Rounded.Close
                        info.reason == PushEndReason.DEVICE_LOST -> Icons.Rounded.LinkOff
                        info.reason == PushEndReason.NETWORK_CHANGED -> Icons.Rounded.WifiOff
                        info.reason == PushEndReason.TAKEN_OVER -> Icons.Rounded.Info
                        else -> Icons.Rounded.CheckCircle
                    },
                    null,
                    tint = when {
                        warn -> ext.onWarningContainer
                        failed -> c.onErrorContainer
                        else -> c.onSurfaceVariant
                    },
                )
            }
            Text(
                title, style = MaterialTheme.typography.headlineSmall, color = c.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() },
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            if (info.reason != PushEndReason.START_FAILED) {
                Column(
                    Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row {
                        Text(tvName, style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.push_end_pushed_for, PushCopy.time(info.durationMs)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    }
                    PushCopy.endSummary(info)?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                }
            }
            // 只推了网址时手机没有分享文件，不写这句（第 1 轮 PM P2-5）
            if (!info.urlOnly && info.reason != PushEndReason.START_FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = ext.success)
                    Text(stringResource(R.string.push_end_sharing_stopped), style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().reportFooterHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                info.canResume -> {
                    Button(onClick = onResume, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.push_end_resume_from, PushCopy.time(info.lastPositionMs))) }
                    FilledTonalButton(onClick = onAgain, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.push_end_again)) }
                }
                info.reason == PushEndReason.START_FAILED && info.failure == com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError.NO_NETWORK -> {
                    Button(onClick = onDiagnostics, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.push_action_diagnostics)) }
                    FilledTonalButton(onClick = onAgain, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.push_action_retry)) }
                }
                else -> Button(onClick = onAgain, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(if (info.reason == PushEndReason.START_FAILED) R.string.push_action_retry else R.string.push_end_again)) }
            }
            FilledTonalButton(onClick = onBack, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.push_back_to_list)) }
        }
    }
}
