package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onSizeChanged

/**
 * 当前页面固定在底部的页脚高度（“停止投屏”、设备列表底栏），Snackbar 据此抬高，不再盖住页脚（U1）。
 */
internal class FooterInset {
    var heightPx by mutableIntStateOf(0)
}

internal val LocalFooterInset = staticCompositionLocalOf { FooterInset() }

/** 标记为页脚：上报实测高度（放在 navigationBarsPadding 之后，不含导航栏），离开组合时归零。 */
internal fun Modifier.reportFooterHeight(): Modifier = composed {
    val inset = LocalFooterInset.current
    DisposableEffect(inset) { onDispose { inset.heightPx = 0 } }
    onSizeChanged { inset.heightPx = it.height }
}
