package com.benchan.wireless.screencasting.feature.sender.elder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.data.settings.ElderTarget
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.asString
import com.benchan.wireless.screencasting.feature.sender.ui.relativeTime
import kotlinx.coroutines.launch

/**
 * 长辈模式开启向导（交互稿 E03，给家人用，普通字号，4 步，可以返回；任何一步退出 = 不开启）：
 * ① 选电视并改叫法（≤2 台、≤8 个字，可跳过）② 预先授权通知与声音 ③ 陪长辈试一次 ④ 记住两件事 → [完成]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderWizardScreen(onDone: () -> Unit, onCancel: () -> Unit, onGoCast: () -> Unit, vm: ElderViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(1) }
    // 选中的设备（顺序 = 首页按钮顺序）与叫法；重新开启时按上次设置预填
    val selected = remember { mutableStateListOf<ElderTarget>() }
    var prefilled by remember { mutableStateOf(false) }
    if (!prefilled && settings != null) {
        prefilled = true
        // 预填用候选里的最新设备名（电视改过名时不沿用旧快照），叫法保留
        settings!!.elderTargets.forEach { t -> candidates.firstOrNull { it.target.key == t.key }?.let { c -> selected += t.copy(name = c.target.name) } }
    }
    var skipped by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun back() = if (step > 1) step-- else onCancel()
    BackHandler { back() }

    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val allGranted = (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) && has(Manifest.permission.RECORD_AUDIO)
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setAudioPreference(granted)
        step = 3
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!has(Manifest.permission.RECORD_AUDIO)) audioLauncher.launch(Manifest.permission.RECORD_AUDIO) else step = 3
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.elder_wizard_title)) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.elder_back)) } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.elder_step, step, 4), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 4 段进度条（UX P2-24）
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i ->
                    androidx.compose.foundation.layout.Box(
                        Modifier.weight(1f).height(4.dp)
                            .background(if (i < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
                    )
                }
            }
            when (step) {
                1 -> {
                    Text(stringResource(R.string.elder_w1_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    if (candidates.isEmpty()) {
                        Text(stringResource(R.string.elder_w1_empty), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onGoCast) { Text(stringResource(R.string.elder_go_cast)) }
                    } else {
                        Text(stringResource(R.string.elder_w1_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val maxTwo = stringResource(R.string.elder_max_two)
                        candidates.forEach { cand ->
                            val idx = selected.indexOfFirst { it.key == cand.target.key }
                            val checked = idx >= 0
                            val ability = stringResource(
                                when {
                                    cand.canMirror && cand.canPush -> R.string.elder_can_both
                                    cand.canMirror -> R.string.elder_can_mirror
                                    else -> R.string.elder_push_only
                                },
                            )
                            val used = cand.lastUsedAt.takeIf { it > 0 }?.let { stringResource(R.string.elder_cand_last, relativeTime(it).asString()) }
                            val sub = listOfNotNull(stringResource(if (cand.trusted) R.string.elder_cand_trusted else R.string.elder_cand_pushed), ability, used).joinToString(" · ")
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                                    .padding(12.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox) { on ->
                                        if (on) {
                                            if (selected.size >= ElderTarget.MAX_TARGETS) scope.launch { snackbar.showSnackbar(maxTwo) }
                                            // 叫法默认留空 = 按钮上用完整设备名（不再截成 8 个字，EMU-F2）
                                            else selected += cand.target.copy(label = "")
                                        } else selected.removeAll { it.key == cand.target.key }
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked, null)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(cand.target.name, style = MaterialTheme.typography.bodyLarge, color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (checked) {
                                    val label = selected[idx].label
                                    val labelCd = stringResource(R.string.elder_label_cd, ElderTarget.MAX_LABEL)
                                    OutlinedTextField(
                                        value = label,
                                        onValueChange = { v -> selected[idx] = selected[idx].copy(label = ElderTarget.clampLabel(v)) },
                                        label = { Text(stringResource(R.string.elder_label_field)) },
                                        placeholder = { Text(stringResource(R.string.elder_label_placeholder, cand.target.name)) },
                                        supportingText = { Text("${ElderTarget.labelLength(label)} / ${ElderTarget.MAX_LABEL}") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = labelCd },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { skipped = true; selected.clear(); step = 2 }) { Text(stringResource(R.string.elder_skip_last)) }
                    }
                }
                2 -> {
                    Text(stringResource(R.string.elder_w2_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.elder_w2_body), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.elder_w2_notif), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.elder_w2_audio), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.elder_w2_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                3 -> {
                    Text(stringResource(R.string.elder_w3_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    val name = selected.firstOrNull()?.let { it.label.ifBlank { it.name } } ?: stringResource(R.string.elder_tv)
                    Text(stringResource(R.string.elder_w3_body, name), style = MaterialTheme.typography.bodyLarge)
                }
                4 -> {
                    Text(stringResource(R.string.elder_w4_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.elder_w4_1), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.elder_w4_2), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                1 -> if (candidates.isNotEmpty()) Button(onClick = { skipped = selected.isEmpty(); step = 2 }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty()) { Text(stringResource(R.string.elder_next)) }
                2 -> Button(onClick = {
                    when {
                        Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        !has(Manifest.permission.RECORD_AUDIO) -> audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else -> {
                            // 早就授权过：也把声音偏好打开，免得长辈一直没声音（PM P2-21 ②）
                            vm.setAudioPreference(true)
                            step = 3
                        }
                    }
                }, Modifier.fillMaxWidth()) { Text(stringResource(if (allGranted) R.string.elder_next else R.string.elder_allow)) }
                // 向导里不真的发起投屏（还没开启长辈模式），按钮如实写“知道了，下一步”（用户拍板）
                3 -> Button(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.elder_got_it_next)) }
                4 -> Button(onClick = { vm.finishWizard(if (skipped) emptyList() else selected.toList(), onDone) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.elder_done)) }
            }
        }
        SnackbarHost(snackbar)
    }
}
