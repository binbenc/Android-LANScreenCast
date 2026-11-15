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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.data.settings.ElderTarget
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
        settings!!.elderTargets.forEach { t -> if (candidates.any { it.target.key == t.key }) selected += t }
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
            title = { Text("设置长辈模式") },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("第 $step 步，共 4 步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("长辈要投到哪台电视？", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    if (candidates.isEmpty()) {
                        Text("请先在普通模式下成功投屏一次，再回来设置", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onGoCast) { Text("去投屏") }
                    } else {
                        Text("最多选 2 台。它们会成为首页上的大按钮，可以改成家里习惯的叫法。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        candidates.forEach { cand ->
                            val idx = selected.indexOfFirst { it.key == cand.target.key }
                            val checked = idx >= 0
                            val ability = when {
                                cand.canMirror && cand.canPush -> "可以投屏，也能放照片"
                                cand.canMirror -> "可以投屏"
                                else -> "只能放照片和视频"
                            }
                            val used = cand.lastUsedAt.takeIf { it > 0 }?.let { " · 上次 " + com.benchan.wireless.screencasting.feature.sender.ui.relativeTime(it) }.orEmpty()
                            val sub = (if (cand.trusted) "已信任 · " else "推送过 · ") + ability + used
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                                    .padding(12.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox) { on ->
                                        if (on) {
                                            if (selected.size >= ElderTarget.MAX_TARGETS) scope.launch { snackbar.showSnackbar("最多选 2 台，先取消一台") }
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
                                    OutlinedTextField(
                                        value = label,
                                        onValueChange = { v -> selected[idx] = selected[idx].copy(label = ElderTarget.clampLabel(v)) },
                                        label = { Text("按钮上的叫法（可以不填）") },
                                        placeholder = { Text("例如：客厅电视。不填就用“${cand.target.name}”") },
                                        supportingText = { Text("${ElderTarget.labelLength(label)} / ${ElderTarget.MAX_LABEL}") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "按钮上的叫法，最多 8 个字" },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { skipped = true; selected.clear(); step = 2 }) { Text("先跳过，用上次投屏的电视") }
                    }
                }
                2 -> {
                    Text("先把权限准备好", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Text("现在允许，长辈以后就不会看到这些系统弹窗。", style = MaterialTheme.typography.bodyLarge)
                    Text("通知：投屏时在通知栏显示“停止”", style = MaterialTheme.typography.bodyMedium)
                    Text("声音：在电视上播放手机的声音", style = MaterialTheme.typography.bodyMedium)
                    Text("不允许也能继续，只是不投声音", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                3 -> {
                    Text("陪长辈试一次", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    val name = selected.firstOrNull()?.let { it.label.ifBlank { it.name } } ?: "电视"
                    Text("完成设置后，请长辈自己按一次“投屏到 $name”，认识一下会弹出的系统提示。", style = MaterialTheme.typography.bodyLarge)
                }
                4 -> {
                    Text("最后，记住两件事", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Text("1. 电视上要打开“局域投屏”才能连上。建议在电视的设置里打开“开机自启”。", style = MaterialTheme.typography.bodyLarge)
                    Text("2. 要退出长辈模式：在长辈首页点“设置”，按住“退出长辈模式”3 秒。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                1 -> if (candidates.isNotEmpty()) Button(onClick = { skipped = selected.isEmpty(); step = 2 }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty()) { Text("下一步") }
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
                }, Modifier.fillMaxWidth()) { Text(if (allGranted) "下一步" else "允许") }
                // 向导里不真的发起投屏（还没开启长辈模式），按钮如实写“知道了，下一步”（用户拍板）
                3 -> Button(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text("知道了，下一步") }
                4 -> Button(onClick = { vm.finishWizard(if (skipped) emptyList() else selected.toList(), onDone) }, Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
        SnackbarHost(snackbar)
    }
}
