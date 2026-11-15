package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey

/** 接收端控制模式（TV 遥控器）：光标（默认）/ 方向键（对方 Android 13+）。 */
enum class TvControlMode { CURSOR, DPAD }

/** 按键来源：遥控器 / 外接全键盘。 */
enum class KeySource { REMOTE, KEYBOARD }

/** 平台无关的按键（接收端把 Android KeyEvent 翻译成它）。 */
enum class InKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK, ESCAPE, META, TAB, ENTER, BACKSPACE, MENU, PLAY_PAUSE, CHAR, OTHER }

/** 一次按键输入。[char] 只在 [InKey.CHAR] 时有效；[alt] / [ctrl] 为修饰键。 */
data class KeyInput(val key: InKey, val source: KeySource, val char: Char? = null, val alt: Boolean = false, val ctrl: Boolean = false)

/** 映射结果。 */
sealed interface KeyAction {
    /** 发给对方的按键 / 全局动作。 */
    data class Send(val key: RemoteKey) : KeyAction

    /** TV 光标模式下移动光标。 */
    data class Cursor(val dir: Direction) : KeyAction

    /** TV 光标模式下确定键：在光标处按下 / 抬起（长按由按住时长自然形成）。 */
    data object CursorPress : KeyAction

    /** 可打印字符 → TXT（50 ms 合并）。 */
    data class Type(val char: Char) : KeyAction

    /** 打开 / 关闭控制条（菜单键）。 */
    data object ToggleBar : KeyAction

    /** 返回键交给调用方（短按 = 对方返回、长按 ≥600 ms = 控制条）。 */
    data object Back : KeyAction

    /** 对方系统版本不支持（提示一次）。 */
    data class Unsupported(val feature: String) : KeyAction

    /** 不处理，交给系统。 */
    data object Ignore : KeyAction
}

/**
 * 反向控制的按键映射（交互稿 12 章“输入映射”，PRD F23 B 期）。纯函数，按发送端能力 [features]（ControlFeatures.forSdk）降级：
 * Android 13 以下没有方向键 / 文字 / 回车删除，16 以下没有播放暂停。
 *
 * - 遥控器：光标模式方向键移动光标、确定 = 光标处点击；方向键模式方向键 / 确定 = 对方焦点导航（DPAD）。
 * - 外接键盘（手机 / 平板）：方向键 = DPAD、Esc = 返回、Meta = 主页、Alt+Tab = 最近任务、Enter / Backspace = 编辑键、可打印字符 = 文字。
 * - TV 接收端按 keycode 而不是设备区分（PM 决策 ④）：DPAD_* / DPAD_CENTER 一律按当前 TV 模式（光标 / 方向键）处理，
 *   即外接键盘的方向键在 TV 上也移动光标；只有字符、Esc、Meta、Alt+Tab、Backspace、KEYCODE_ENTER 用键盘语义（由调用方据此设置 [KeyInput.source]）。
 */
object KeyMapper {
    fun map(input: KeyInput, mode: TvControlMode, features: Set<String>): KeyAction {
        val dpad = ControlFeatures.DPAD in features
        val text = ControlFeatures.TEXT in features
        val media = ControlFeatures.MEDIA in features
        fun dir(k: InKey) = when (k) {
            InKey.UP -> Direction.UP
            InKey.DOWN -> Direction.DOWN
            InKey.LEFT -> Direction.LEFT
            else -> Direction.RIGHT
        }
        fun dpadKey(k: InKey) = when (k) {
            InKey.UP -> RemoteKey.DPAD_UP
            InKey.DOWN -> RemoteKey.DPAD_DOWN
            InKey.LEFT -> RemoteKey.DPAD_LEFT
            InKey.RIGHT -> RemoteKey.DPAD_RIGHT
            else -> RemoteKey.DPAD_CENTER
        }
        return when (input.key) {
            InKey.BACK -> KeyAction.Back
            InKey.MENU -> KeyAction.ToggleBar
            InKey.PLAY_PAUSE -> if (media) KeyAction.Send(RemoteKey.PLAY_PAUSE) else KeyAction.Unsupported(ControlFeatures.MEDIA)
            InKey.UP, InKey.DOWN, InKey.LEFT, InKey.RIGHT -> when {
                input.source == KeySource.REMOTE && mode == TvControlMode.CURSOR -> KeyAction.Cursor(dir(input.key))
                dpad -> KeyAction.Send(dpadKey(input.key))
                // 遥控器在方向键模式但对方不支持（模式按钮本应不可用）：退回光标
                input.source == KeySource.REMOTE -> KeyAction.Cursor(dir(input.key))
                else -> KeyAction.Unsupported(ControlFeatures.DPAD)
            }
            InKey.CENTER -> when {
                input.source == KeySource.REMOTE && mode == TvControlMode.DPAD && dpad -> KeyAction.Send(RemoteKey.DPAD_CENTER)
                input.source == KeySource.REMOTE -> KeyAction.CursorPress
                else -> KeyAction.Ignore
            }
            InKey.ESCAPE -> KeyAction.Send(RemoteKey.BACK)
            InKey.META -> KeyAction.Send(RemoteKey.HOME)
            // 单独的 Tab 不处理（避免误触；Alt+Tab = 最近任务）
            InKey.TAB -> if (input.alt) KeyAction.Send(RemoteKey.RECENTS) else KeyAction.Ignore
            InKey.ENTER -> when {
                // 遥控器的 ENTER 等同确定键
                input.source == KeySource.REMOTE -> map(input.copy(key = InKey.CENTER), mode, features)
                text -> KeyAction.Send(RemoteKey.ENTER)
                else -> KeyAction.Unsupported(ControlFeatures.TEXT)
            }
            InKey.BACKSPACE -> if (text) KeyAction.Send(RemoteKey.DEL) else KeyAction.Unsupported(ControlFeatures.TEXT)
            InKey.CHAR -> {
                val c = input.char
                when {
                    c == null || input.ctrl || input.alt -> KeyAction.Ignore
                    !text -> KeyAction.Unsupported(ControlFeatures.TEXT)
                    else -> KeyAction.Type(c)
                }
            }
            InKey.OTHER -> KeyAction.Ignore
        }
    }
}
