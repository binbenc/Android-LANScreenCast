package com.benchan.wireless.screencasting.feature.control

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.control.RectD
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用进程与 [RemoteControlService] 之间的桥（同进程）。服务由系统绑定，连接 / 断开时在这里登记；
 * 授权编排（[SenderControlCoordinator]）只通过本类拿注入器、显示边缘胶囊，不直接持有服务。
 */
@Singleton
class AccessibilityBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _service = MutableStateFlow<RemoteControlService?>(null)

    /** 服务是否已被系统绑定（真正可以注入）。 */
    val connected: StateFlow<Boolean> get() = _connected.asStateFlow()
    private val _connected = MutableStateFlow(false)

    /** R03 确认页是否正在显示：显示期间丢弃来自接收端的任何注入（防止对方自己点“允许”）。 */
    @Volatile
    var consentVisible: Boolean = false

    /** 边缘胶囊当前的屏幕矩形（远端注入不能点中）。 */
    @Volatile
    var overlayRect: RectD? = null

    /** 吸收窗口的中心点（中止手势派发到这里，用户决策 D5）与矩形（远端注入不能点中）。 */
    @Volatile
    var sinkPoint: com.benchan.wireless.screencasting.core.control.PointD? = null

    @Volatile
    var sinkRect: RectD? = null

    /** 投屏会话开始：放置吸收窗口。 */
    fun showSink() {
        _service.value?.showSink()
    }

    /** 投屏会话结束：稍后移除吸收窗口（等最后一个取消手势被系统处理）。 */
    fun hideSink() {
        _service.value?.hideSink(delayMs = 500)
    }

    internal fun attach(service: RemoteControlService) {
        _service.value = service
        _connected.value = true
        WscLog.i(TAG, "accessibility service connected")
    }

    internal fun detach(service: RemoteControlService) {
        if (_service.value === service) {
            _service.value = null
            _connected.value = false
            overlayRect = null
            WscLog.i(TAG, "accessibility service disconnected")
        }
    }

    /** 前台窗口切换（只有包名 / 类名，C 期敏感界面拦截）。监听者为协调器。 */
    @Volatile
    var windowStateListener: ((packageName: String?, className: String?) -> Unit)? = null

    internal fun onWindowState(packageName: String?, className: String?) {
        windowStateListener?.invoke(packageName, className)
    }

    private val _advancedProtection = MutableStateFlow(false)

    /** Android 17 高级保护模式是否开启（启动时查询，并注册回调实时更新）。 */
    val advancedProtection: StateFlow<Boolean> = _advancedProtection.asStateFlow()

    init {
        _advancedProtection.value = advancedProtectionBlocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            runCatching {
                context.getSystemService(AdvancedProtectionManager::class.java)?.registerAdvancedProtectionCallback(context.mainExecutor) { enabled ->
                    _advancedProtection.value = enabled
                }
            }.onFailure { WscLog.w(TAG, "advanced protection callback unavailable: ${it.javaClass.simpleName}") }
        }
    }

    /** 当前可用的注入器；服务未连接时为 null。 */
    val injector: InputInjector? get() = _service.value?.injector

    /** 边缘胶囊（交互稿 R05）。服务未连接时不显示。 */
    fun showOverlay(state: OverlayState, onRevoke: () -> Unit, onContinue: () -> Unit = {}) {
        _service.value?.showOverlay(state, onRevoke, onContinue)
    }

    fun hideOverlay() {
        _service.value?.hideOverlay()
        overlayRect = null
    }

    /** 状态栏 / 通知栏区域（屏幕坐标）：远端注入不能从这里开始（交互稿 R05 防误触）。 */
    fun statusBarRect(): RectD? = _service.value?.statusBarRect()

    /**
     * 关闭总开关时让服务自行停用（交互稿 R01：不留空挂的无障碍服务）。
     * 服务未连接时无事可做（系统设置里已经是关闭状态，或被 ROM 停掉了）。
     */
    fun disableService() {
        _service.value?.let {
            WscLog.i(TAG, "disableSelf()")
            it.disableSelf()
        }
    }

    /** 系统设置里是否已打开本服务（从系统设置返回时自动检测，交互稿 R02）。 */
    fun isServiceEnabledInSettings(): Boolean {
        if (_connected.value) return true
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, RemoteControlService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * Android 17 高级保护模式：系统禁止非辅助工具使用无障碍服务（本服务如实声明 isAccessibilityTool=false）。
     * 查询失败按未开启处理（系统仍会自行拦截，设置页只是少一句说明）。【需真机验证】
     */
    fun advancedProtectionBlocks(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return false
        return runCatching {
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled == true
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "A11yBridge"
    }
}

/** 边缘胶囊显示的状态（交互稿 R05）。 */
data class OverlayState(val paused: Boolean, val label: String, val canContinue: Boolean = false)
