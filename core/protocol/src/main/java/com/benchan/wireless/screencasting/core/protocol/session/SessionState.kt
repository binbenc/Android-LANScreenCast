package com.benchan.wireless.screencasting.core.protocol.session

/**
 * WSC 会话状态（技术方案 3.5.6）：
 * ```
 * DISCONNECTED → CONNECTING → (发送端 HELLO_SENT / 接收端 AWAIT_HELLO)
 *   → (trusted && !confirm ? AUTHENTICATED : PAIRING) → AUTHENTICATED
 * AUTHENTICATED → NEGOTIATING (OFFER/ANSWER/ICE) → STREAMING
 * STREAMING/NEGOTIATING ⇄ RECONNECTING → RESUMING → NEGOTIATING（ICE restart）
 * 任意 → CLOSED（BYE / 30 秒超时 / 致命 ERROR）
 * ```
 */
enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    /** 发送端已发出 HELLO，等待接收端 HELLO。 */
    HELLO_SENT,
    /** 接收端已建立 TLS，等待发送端 HELLO。 */
    AWAIT_HELLO,
    PAIRING,
    AUTHENTICATED,
    NEGOTIATING,
    STREAMING,
    /** 信令断开，正在退避重连（保留录屏会话）。 */
    RECONNECTING,
    /** 重连后已发送 HELLO{resume=true}，等待 resumeOk。 */
    RESUMING,
    CLOSED;

    /** 只有认证后的状态才能交换 SDP / ICE（技术方案 3.5.5 约束 1）。 */
    val isAuthenticated: Boolean
        get() = this == AUTHENTICATED || this == NEGOTIATING || this == STREAMING
}
