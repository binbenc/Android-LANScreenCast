package com.benchan.wireless.screencasting.feature.control.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.benchan.wireless.screencasting.core.ui.theme.WscAppTheme
import dagger.hilt.android.AndroidEntryPoint
import com.benchan.wireless.screencasting.feature.control.R

/** 交互稿 R02 的独立入口：对方请求控制而本机无障碍服务未开启时，从通知进入开启步骤。 */
@AndroidEntryPoint
class AccessibilityGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WscAppTheme {
                AccessibilityGuideScreen(
                    onBack = { finish() },
                    onEnabled = {
                        Toast.makeText(this, getString(R.string.ctl_guide_enabled_toast), Toast.LENGTH_SHORT).show()
                        finish()
                    },
                )
            }
        }
    }
}
