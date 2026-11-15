package com.benchan.wireless.screencasting.feature.sender.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.ui.theme.WscAppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 系统分享菜单“推送到电视”（F14，PRD 8.1.2 B，交互稿第 14 章）。
 * 独立任务（taskAffinity=""、不进最近任务、不经过引导页 / 模式选择 / 首页，不改默认模式）；
 * 以底部面板的形式盖在来源 App 上，处理完即结束。推送开始后由前台服务持有，关闭本页不会取消。
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.handleOnce(intent)
        setContent {
            WscAppTheme(tv = false) {
                val snackbar = remember { SnackbarHostState() }
                val settings by vm.settings.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    vm.effects.collect { e ->
                        when (e) {
                            ShareEffect.Finish -> finish()
                            is ShareEffect.Toast -> Toast.makeText(this@ShareActivity, e.text.resolve(this@ShareActivity), Toast.LENGTH_SHORT).show()
                            is ShareEffect.Snackbar -> snackbar.showSnackbar(e.text.resolve(this@ShareActivity))
                            is ShareEffect.OpenApp -> openApp(e)
                        }
                    }
                }
                // 设置读出来之前不画面板，避免先按普通版画、再跳成长辈版（UX P2-4）
                settings?.let { ShareScreen(vm, snackbar, elder = it.elderModeEnabled, onClose = ::finish) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleNew(intent)
    }

    private fun openApp(e: ShareEffect.OpenApp) {
        val main = SenderIntents.main(this) ?: return finish()
        when {
            e.remote -> main.putExtra(SenderIntents.EXTRA_OPEN_PUSH_REMOTE, true)
            e.mirrorHint -> main.putExtra(SenderIntents.EXTRA_MIRROR_HINT, true)
            e.pushUdn != null -> main.putExtra(SenderIntents.EXTRA_PUSH_UDN, e.pushUdn)
        }
        runCatching { startActivity(main) }.onFailure { WscLog.w("Share", "open app failed", it) }
        finish()
    }
}
