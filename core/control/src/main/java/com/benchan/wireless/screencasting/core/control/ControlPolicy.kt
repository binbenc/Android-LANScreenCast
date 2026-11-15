package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import com.benchan.wireless.screencasting.core.protocol.control.RejectReason
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey

/**
 * 注入前的环境快照（发送端每个手势开始 / 每个按键前采集）。
 *
 * @property canInject 授权状态机处于 ACTIVE。
 * @property keyguardLocked 已锁屏（锁屏 = 不可控制）。
 * @property consentVisible 控制确认页 R03 正在显示（防止对方自己点“允许”）。
 * @property blocked 屏幕坐标上禁止远端触达的区域：边缘胶囊、状态栏 / 通知栏（交互稿 R05 防误触）。
 */
data class InjectionContext(
    val canInject: Boolean,
    val keyguardLocked: Boolean,
    val consentVisible: Boolean,
    val blocked: List<RectD>,
)

/**
 * 反向控制的注入门控（PRD F23 风险表“无障碍服务滥用”的缓解措施；技术方案“反向控制”5 节）。纯函数，JVM 单测覆盖。
 *
 * 规则只在**手势开始**（DOWN）时判断落点：一旦允许，同一手势后续的移动即使经过受保护区域也继续
 * （与系统触摸分发一致：触摸目标由 DOWN 决定），但授权 / 锁屏 / 确认页任何时候变化都会立即中止。
 */
object ControlPolicy {
    /** 手势开始前的检查；返回 null = 允许。 */
    fun checkDown(x: Double, y: Double, ctx: InjectionContext): RejectReason? = when {
        !ctx.canInject -> RejectReason.PAUSED
        ctx.keyguardLocked -> RejectReason.LOCKED
        ctx.consentVisible -> RejectReason.BLOCKED_TARGET
        ctx.blocked.any { PointD(x, y) in it } -> RejectReason.BLOCKED_TARGET
        else -> null
    }

    /** 手势进行中的检查（移动 / 抬起前）；返回 null = 继续。 */
    fun checkOngoing(ctx: InjectionContext): RejectReason? = when {
        !ctx.canInject -> RejectReason.PAUSED
        ctx.keyguardLocked -> RejectReason.LOCKED
        ctx.consentVisible -> RejectReason.BLOCKED_TARGET
        else -> null
    }

    /**
     * 按键检查：只放行 [RemoteKey.WHITELIST]，并按发送端能力 [features]（ControlFeatures.forSdk）降级：
     * 方向键需要 dpad、回车 / 删除需要 text、播放暂停需要 media；不满足回 UNSUPPORTED。
     */
    fun checkKey(key: RemoteKey, ctx: InjectionContext, features: Set<String>): RejectReason? = when {
        !ctx.canInject -> RejectReason.PAUSED
        ctx.keyguardLocked -> RejectReason.LOCKED
        ctx.consentVisible -> RejectReason.BLOCKED_TARGET
        key !in RemoteKey.WHITELIST -> RejectReason.UNSUPPORTED
        key in RemoteKey.DPAD && ControlFeatures.DPAD !in features -> RejectReason.UNSUPPORTED
        key in RemoteKey.EDITOR && ControlFeatures.TEXT !in features -> RejectReason.UNSUPPORTED
        key == RemoteKey.PLAY_PAUSE && ControlFeatures.MEDIA !in features -> RejectReason.UNSUPPORTED
        else -> null
    }

    /** 文字输入检查（输入框与密码框由注入器判断）。 */
    fun checkText(ctx: InjectionContext, features: Set<String>): RejectReason? = when {
        !ctx.canInject -> RejectReason.PAUSED
        ctx.keyguardLocked -> RejectReason.LOCKED
        ctx.consentVisible -> RejectReason.BLOCKED_TARGET
        ControlFeatures.TEXT !in features -> RejectReason.UNSUPPORTED
        else -> null
    }
}

/**
 * IREJ 限速：同一原因每 [intervalMs] 最多回一条（接收端只需要“提示一次”）。不是线程安全的。
 */
class RejectLimiter(private val clock: () -> Long, private val intervalMs: Long = 1_000) {
    private val lastAt = HashMap<RejectReason, Long>()

    fun shouldSend(reason: RejectReason): Boolean {
        val now = clock()
        val prev = lastAt[reason]
        if (prev != null && now - prev < intervalMs) return false
        lastAt[reason] = now
        return true
    }
}

/** 序号门：丢弃不递增的输入（PROTOCOL.md 4.2：允许空洞）。新会话 / 新授权时 [reset]。不是线程安全的。 */
class SequenceGate {
    private var last = -1L

    fun accept(seq: Long): Boolean {
        if (seq <= last) return false
        last = seq
        return true
    }

    fun reset() {
        last = -1L
    }
}

/**
 * 输入框是否为密码类（android.text.InputType 常量，按值内联以便 JVM 单测）：
 * TEXT 类的 PASSWORD / VISIBLE_PASSWORD / WEB_PASSWORD，NUMBER 类的 NUMBER_VARIATION_PASSWORD。密码框拒绝远程输入。
 */
object InputTypes {
    private const val MASK_CLASS = 0x0000000f
    private const val MASK_VARIATION = 0x00000ff0
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private const val TEXT_PASSWORD = 0x00000080
    private const val TEXT_VISIBLE_PASSWORD = 0x00000090
    private const val TEXT_WEB_PASSWORD = 0x000000e0
    private const val NUMBER_PASSWORD = 0x00000010

    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and MASK_VARIATION
        return when (inputType and MASK_CLASS) {
            CLASS_TEXT -> variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD
            CLASS_NUMBER -> variation == NUMBER_PASSWORD
            else -> false
        }
    }
}

/**
 * 敏感界面规则（数据表，PM 建议 2）：[packageName] 精确匹配；[classContains] 为空 = 整个包都算敏感，否则类名包含任一片段才算。
 */
data class SensitiveRule(val packageName: String, val classContains: List<String> = emptyList(), val note: String = "")

/**
 * 前台敏感界面（PRD F23 C 期，用户决策 D-2）：只按无障碍事件 TYPE_WINDOW_STATE_CHANGED 的包名 / 类名判断（不读取界面内容）。
 * 前台是系统安装器、权限弹窗、无障碍设置、系统录屏授权 / USB 与无线调试授权时暂停注入。本应用自身界面由 D9 另行处理。
 *
 * 已知缺口（如实告知）：从设置首页点进“无障碍”时页面类名是通用的 `SubSettings`，识别不到（模拟器已确认）；不在表中的 ROM 安装界面同理。
 */
object SensitiveApps {
    val RULES: List<SensitiveRule> = listOf(
        // 安装器
        SensitiveRule("com.android.packageinstaller", note = "AOSP 安装器"),
        SensitiveRule("com.google.android.packageinstaller", note = "Google 安装器"),
        SensitiveRule("com.miui.packageinstaller", note = "MIUI / HyperOS 安装器"),
        SensitiveRule("com.samsung.android.packageinstaller", note = "三星安装器"),
        SensitiveRule("com.oplus.packageinstaller", note = "OPPO / 一加 安装器"),
        SensitiveRule("com.coloros.packageinstaller", note = "ColorOS 旧版安装器"),
        SensitiveRule("com.heytap.packageinstaller", note = "HeyTap 安装器"),
        SensitiveRule("com.vivo.packageinstaller", note = "vivo 安装器"),
        SensitiveRule("com.huawei.packageinstaller", note = "华为安装器"),
        SensitiveRule("com.hihonor.packageinstaller", note = "荣耀安装器"),
        // 权限弹窗（运行时权限、特殊权限授予）
        SensitiveRule("com.android.permissioncontroller", note = "AOSP 权限控制器"),
        SensitiveRule("com.google.android.permissioncontroller", note = "Google 权限控制器"),
        // 无障碍设置
        SensitiveRule("com.samsung.accessibility", note = "三星无障碍设置"),
        SensitiveRule("com.android.accessibility", note = "部分 ROM 的无障碍设置"),
        SensitiveRule("com.android.settings", listOf("Accessibility"), "设置中的无障碍页（直接打开时类名含 Accessibility；从首页进入的 SubSettings 识别不到）"),
        SensitiveRule("com.miui.securitycenter", listOf("Accessibility"), "MIUI 安全中心的无障碍相关页"),
        SensitiveRule("com.coloros.settings", listOf("Accessibility"), "ColorOS 设置无障碍页"),
        SensitiveRule("com.oplus.settings", listOf("Accessibility"), "OPPO 设置无障碍页"),
        // 系统界面中的授权对话框（PM S3）：录屏授权、USB 调试、无线调试配对
        SensitiveRule("com.android.systemui", listOf("MediaProjectionPermissionActivity", "UsbDebuggingActivity", "WifiDebuggingActivity", "WirelessDebugging"), "系统录屏 / 调试授权"),
    )

    private val byPackage: Map<String, SensitiveRule> = RULES.associateBy { it.packageName }

    /** 常见系统 / ROM 安装器（兼容旧引用）。 */
    val INSTALLERS: Set<String> = RULES.filter { it.note.contains("安装器") }.map { it.packageName }.toSet()

    fun isSensitive(packageName: String?, className: String?): Boolean {
        val rule = byPackage[packageName ?: return false] ?: return false
        if (rule.classContains.isEmpty()) return true
        val cls = className.orEmpty()
        return rule.classContains.any { cls.contains(it, ignoreCase = true) }
    }
}

/**
 * 前台敏感状态跟踪（PM S2“失败向收紧”）：已处于敏感状态时，输入法窗口、Toast 类事件、`android` 框架包、
 * 系统界面的普通窗口（通知栏 / 音量条）与本应用自己的窗口**不会**清除敏感状态；只有真正切到另一个普通应用 / 桌面才恢复。
 * 不是线程安全的（协调器单线程调用）。
 */
class SensitiveTracker(private val ownPackage: String) {
    var sensitive: Boolean = false
        private set

    /**
     * 处理一次前台窗口切换事件。
     * @param imePackages 已启用输入法的包名（由调用方从 InputMethodManager 取得）。
     * @return 敏感状态是否变化。
     */
    fun onWindow(packageName: String?, className: String?, imePackages: Set<String>): Boolean {
        val pkg = packageName ?: return false
        val next = when {
            SensitiveApps.isSensitive(pkg, className) -> true
            // 以下来源不改变当前判断：本应用窗口（D9 另管）、系统界面普通窗口、输入法、框架包、Toast
            pkg == ownPackage -> sensitive
            pkg == SYSTEM_UI -> sensitive
            pkg in imePackages -> sensitive
            pkg == FRAMEWORK -> sensitive
            className.orEmpty().contains("Toast", ignoreCase = true) -> sensitive
            else -> false
        }
        if (next == sensitive) return false
        sensitive = next
        return true
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val FRAMEWORK = "android"
    }
}

/** 投屏结束时是否让无障碍服务自行停用（R01“投屏结束后自动关闭无障碍服务”，默认关）。 */
object AutoDisablePolicy {
    fun shouldDisableOnSessionEnd(autoDisableEnabled: Boolean, serviceConnected: Boolean, remoteControlEnabled: Boolean): Boolean =
        autoDisableEnabled && serviceConnected && remoteControlEnabled
}
