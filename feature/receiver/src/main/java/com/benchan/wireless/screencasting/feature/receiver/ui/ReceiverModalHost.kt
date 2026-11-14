package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.benchan.wireless.screencasting.core.ui.components.LocalModalOverlayVisible
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benchan.wireless.screencasting.feature.receiver.session.ConfirmRequest
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverController
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleCompatState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject


@HiltViewModel
class ReceiverModalViewModel @Inject constructor(private val controller: ReceiverController) : ViewModel() {
    val request: StateFlow<ConfirmRequest?> = controller.confirmRequest
    val appleState: StateFlow<AppleCompatState> = controller.appleState
    fun respond(id: Long, allowed: Boolean) = controller.respondToRequest(id, allowed)
}

/**
 * 在 Activity 根部（NavHost 之上）渲染接收端弹层：停在设置等任何页面、或被通知拉起时都能看到并确认（P1）。
 */
@Composable
fun ReceiverModalHost(viewModel: ReceiverModalViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val request by viewModel.request.collectAsStateWithLifecycle()
    val apple by viewModel.appleState.collectAsStateWithLifecycle()
    // 用户按返回 / 超时关掉的 PIN（同一个 PIN 不再弹出；UxPlay 发起新配对会生成新 PIN）
    var dismissedPin by remember { mutableStateOf<String?>(null) }
    val pin = (apple as? AppleCompatState.Advertising)?.pin?.takeIf { it != dismissedPin }
    val modalVisible = request != null || pin != null
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalModalOverlayVisible provides modalVisible) { content() }
        if (pin != null && request == null) AppleCompatPinOverlay(pin, onDismiss = { dismissedPin = pin })
        request?.let { r -> ConfirmDialog(r) { allowed -> viewModel.respond(r.id, allowed) } }
    }
}
