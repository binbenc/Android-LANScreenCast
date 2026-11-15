package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 不可用态统一用 surfaceContainer 底 + outline 字（交互稿深色规则：不叠透明度），替代 M3 默认的 onSurface@38%。
 */
object WscButtonColors {
    @Composable
    fun filled(): ButtonColors = MaterialTheme.colorScheme.let { c ->
        ButtonDefaults.buttonColors(disabledContainerColor = c.surfaceContainer, disabledContentColor = c.outline)
    }

    @Composable
    fun tonal(): ButtonColors = MaterialTheme.colorScheme.let { c ->
        ButtonDefaults.filledTonalButtonColors(disabledContainerColor = c.surfaceContainer, disabledContentColor = c.outline)
    }

    @Composable
    fun icon(): IconButtonColors = MaterialTheme.colorScheme.let { c ->
        IconButtonDefaults.iconButtonColors(disabledContentColor = c.outline)
    }

    @Composable
    fun filledIcon(): IconButtonColors = MaterialTheme.colorScheme.let { c ->
        IconButtonDefaults.filledIconButtonColors(disabledContainerColor = c.surfaceContainer, disabledContentColor = c.outline)
    }
}
