package com.benchan.wireless.screencasting.ui

import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.data.settings.AppMode

/**
 * 交互稿 01：首次引导 · 承诺 + 模式选择。承诺写可验证的事实；本页不申请任何权限；模式卡单选、整卡可点（≥108dp）。
 */
@Composable
fun OnboardingScreen(defaultMode: AppMode, onStart: (AppMode) -> Unit, onInstallHelp: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(defaultMode) }
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.app_onboarding_headline), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.app_onboarding_subtitle), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        Promise(Icons.Rounded.DoNotDisturbOn, stringResource(R.string.app_onboarding_no_ads_title), stringResource(R.string.app_onboarding_no_ads_detail))
        Promise(Icons.Rounded.PersonOff, stringResource(R.string.app_onboarding_no_account_title), stringResource(R.string.app_onboarding_no_account_detail))
        Promise(Icons.Rounded.CloudOff, stringResource(R.string.app_onboarding_offline_title), stringResource(R.string.app_onboarding_offline_detail))
        Text(stringResource(R.string.app_onboarding_use_as), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ModeCard(mode == AppMode.SENDER, Icons.Rounded.ScreenShare, stringResource(R.string.app_onboarding_mode_sender_title), recommended(stringResource(R.string.app_onboarding_mode_sender_subtitle), defaultMode == AppMode.SENDER)) { mode = AppMode.SENDER }
        ModeCard(mode == AppMode.RECEIVER, Icons.Rounded.Tv, stringResource(R.string.app_onboarding_mode_receiver_title), recommended(stringResource(R.string.app_onboarding_mode_receiver_subtitle), defaultMode == AppMode.RECEIVER)) { mode = AppMode.RECEIVER }
        Button(onClick = { onStart(mode) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.app_onboarding_start)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_onboarding_tv_missing), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            TextButton(onClick = onInstallHelp) { Text(stringResource(R.string.app_onboarding_install_help)) }
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeCard(selected: Boolean, icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 108.dp)
            .background(if (selected) c.primaryContainer else c.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outlineVariant, RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = if (selected) c.onPrimaryContainer else c.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        RadioButton(selected, onClick = null)
    }
}

/** 模式卡副标题：本机推荐的模式追加“ · Recommended for this device”。 */
@Composable
private fun recommended(subtitle: String, isDefault: Boolean): String =
    if (isDefault) stringResource(R.string.app_onboarding_mode_recommended, subtitle) else subtitle
