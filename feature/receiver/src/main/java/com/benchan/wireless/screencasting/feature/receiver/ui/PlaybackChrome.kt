package com.benchan.wireless.screencasting.feature.receiver.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.ui.theme.ScrimAlpha
import com.benchan.wireless.screencasting.core.ui.theme.onScrim
import kotlinx.coroutines.delay

/** 来源标签的显示请求计数：开始播放时显示一次，之后按菜单 / 确定键再显示（U5）。 */
class SourceTagState {
    var showRequests by mutableIntStateOf(0)
        private set

    fun show() {
        showRequests++
    }
}

@Composable
internal fun rememberSourceTagState(): SourceTagState = remember { SourceTagState() }

/** 放在播放页根节点：菜单 / 确定键时重新显示来源标签；不消费按键，播放器控制条照常响应。 */
internal fun Modifier.showSourceTagOnKeys(state: SourceTagState): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown) {
        when (e.key.nativeKeyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> state.show()
        }
    }
    false
}

/** 视频上方的来源标签：scrim 60% 底、文字不再叠加透明度；显示 3 秒后淡出（U5）。 */
@Composable
internal fun FadingSourceTag(text: String, state: SourceTagState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(state.showRequests) {
        visible = true
        delay(SOURCE_TAG_VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            text,
            Modifier.padding(horizontal = 48.dp, vertical = 27.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha.Text), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onScrim, style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val SOURCE_TAG_VISIBLE_MS = 3_000L

/**
 * 播放页沉浸式（E6）：隐藏状态栏和导航栏（手机 / 平板当接收端时，浅色导航栏不再压在黑色视频上），
 * 从边缘滑动可临时呼出；离开播放页恢复。TV 上通常没有系统栏，等同空操作。
 */
@Composable
internal fun ImmersiveSystemBars() {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
