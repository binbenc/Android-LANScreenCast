package com.benchan.wireless.screencasting.feature.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.benchan.wireless.screencasting.core.ui.components.tvFocusRing
import com.benchan.wireless.screencasting.core.ui.components.tvInitialFocus

/**
 * 设置类页面统一的标题栏。TV 上返回按钮有明显的焦点外环（B7）；[initialFocus] 为 true 时进入页面焦点落在返回按钮
 * （页面没有更合适的第一项时使用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    initialFocus: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = (if (initialFocus) Modifier.tvInitialFocus() else Modifier).tvFocusRing(),
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        },
        actions = actions,
    )
}
