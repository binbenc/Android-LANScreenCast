package com.benchan.wireless.screencasting.feature.control

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import com.benchan.wireless.screencasting.core.control.InputTypes
import com.benchan.wireless.screencasting.core.protocol.control.RejectReason
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey

/**
 * 文字输入（PRD F23 B 期，Android 13+）：通过 AccessibilityService.InputMethod（服务声明 flagInputMethodEditor）
 * 把对方输入的文字提交到**当前获得焦点的输入框**。不读取输入框内容；按 EditorInfo.inputType 拒绝密码框。
 * 用户正在使用的软键盘不受影响。【需真机验证】各 ROM / 输入法共存时的行为。
 */
@RequiresApi(33)
internal object A11yText {
    fun commit(service: AccessibilityService, text: String): RejectReason? {
        val im = service.inputMethod ?: return RejectReason.NO_EDITOR
        val ic = im.currentInputConnection ?: return RejectReason.NO_EDITOR
        if (isSecure(im.currentInputEditorInfo)) return RejectReason.SECURE_FIELD
        ic.commitText(text, 1, null)
        return null
    }

    fun key(service: AccessibilityService, key: RemoteKey): RejectReason? {
        val code = when (key) {
            RemoteKey.ENTER -> KeyEvent.KEYCODE_ENTER
            RemoteKey.DEL -> KeyEvent.KEYCODE_DEL
            else -> return RejectReason.UNSUPPORTED
        }
        val im = service.inputMethod ?: return RejectReason.NO_EDITOR
        val ic = im.currentInputConnection ?: return RejectReason.NO_EDITOR
        val info = im.currentInputEditorInfo
        if (isSecure(info)) return RejectReason.SECURE_FIELD
        // 单行输入框的回车优先执行 IME 动作（搜索 / 发送 / 前往），与软键盘一致
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
        if (key == RemoteKey.ENTER && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            (info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        ) {
            ic.performEditorAction(action)
            return null
        }
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return null
    }

    private fun isSecure(info: EditorInfo?): Boolean = info != null && InputTypes.isPassword(info.inputType)
}
