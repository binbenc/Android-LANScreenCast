package com.benchan.wireless.screencasting.feature.sender.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.feature.sender.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 从本应用以外打开 MainActivity 时的意图附加项（F14 分享页 / Direct Share 桌面快捷方式）。 */
object SenderIntents {
    /** 分享页 S03 [打开遥控]：打开本次推送的遥控页。 */
    const val EXTRA_OPEN_PUSH_REMOTE = "wsc.open_push_remote"

    /** 分享页 S04 [改用屏幕镜像]：设备列表顶部显示“选好电视开始镜像后，回到视频 App 播放即可”。 */
    const val EXTRA_MIRROR_HINT = "wsc.mirror_hint"

    /** S05 ⑦“在本应用里重新选”：为这台 DLNA 设备打开内容面板（P03，之后仍要用户选内容）。 */
    const val EXTRA_PUSH_UDN = "wsc.push_udn"

    /** 桌面长按菜单里的 Direct Share 快捷方式：快捷方式 ID（随机令牌，本机解析为 UDN）。 */
    const val EXTRA_SHORTCUT = "wsc.push_shortcut"

    /** 本应用的启动 Activity（feature 模块拿不到 MainActivity 类）。 */
    fun main(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * F14 Direct Share（PRD 8.1.2 H，决策 #34，交互稿 S08）：系统分享面板里直接显示最近推送成功的电视。
 * - 最多 3 个；只放 30 天内推送成功过的设备（都已通过首次确认），按最近一次成功时间排序。
 * - 电视匹配视频 / 照片 / 音乐 / 文字；音箱只匹配音乐（res/xml/shortcuts.xml 里两个 share-target 分类）。
 * - 推送成功、忘记设备、设置开关变化时自动更新（观察 [DlnaRendererStore] 与设置）；关闭开关立即移除全部。
 * - 只用 androidx.core 的系统快捷方式接口，不加权限、不加依赖；快捷方式只登记在本机系统里。
 * - 快捷方式 ID 是每台设备一个**随机令牌**（本机 `direct_share_tokens.json` 保存“令牌 → UDN”），不暴露 UDN；
 *   设备移出时令牌作废、被固定到桌面的快捷方式置为不可用。其他 App 伪造的 ID 解析不出 UDN，一律落回选设备页（PM P1-3）。
 */
@Singleton
class DirectShareShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val renderers: DlnaRendererStore,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val tokens = com.benchan.wireless.screencasting.core.data.JsonFileStore(
        java.io.File(context.noBackupFilesDir, "direct_share_tokens.json"),
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()),
    ) { emptyMap() }

    /** 分享面板 / 桌面点了快捷方式：ID → UDN；未知令牌（伪造、已作废）返回 null。 */
    fun udnForShortcut(id: String?): String? = parseToken(id)?.let { tokens.read()[it] }

    private fun tokenFor(udn: String): String =
        tokens.read().entries.firstOrNull { it.value == udn }?.key
            ?: java.util.UUID.randomUUID().toString().replace("-", "").also { t -> tokens.update { it + (t to udn) } }

    /** App 启动时调用一次。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // 每天按当前时间重算一次 30 天窗口（App 长时间不打开时也能移除过期的快捷方式，PM P2-10）
            val daily = kotlinx.coroutines.flow.flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    kotlinx.coroutines.delay(DAY_MS)
                }
            }
            combine(renderers.records, settings.settings.map { it.directShareEnabled }.distinctUntilChanged(), daily) { records, enabled, now ->
                if (enabled) pick(records, now) else emptyList()
            }.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun apply(picks: List<Pick>) {
        runCatching {
            val keepUdns = picks.map { it.udn }.toSet()
            // 移出的设备：令牌作废，动态快捷方式删除，固定到桌面的置为不可用（PM P2-11）
            val removedTokens = tokens.read().filterValues { it !in keepUdns }.keys
            if (removedTokens.isNotEmpty()) tokens.update { m -> m.filterValues { it in keepUdns } }
            val existing = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(PREFIX) }
            val keep = picks.map { PREFIX + tokenFor(it.udn) }.toSet()
            val stale = (existing + removedTokens.map { PREFIX + it }).distinct().filterNot { it in keep }
            if (stale.isNotEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
                runCatching { ShortcutManagerCompat.disableShortcuts(context, stale, "这台电视已从推送列表移除") }
            }
            val launch = SenderIntents.main(context)?.component
            picks.forEachIndexed { rank, p ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    if (launch != null) component = launch else setPackage(context.packageName)
                    putExtra(SenderIntents.EXTRA_SHORTCUT, PREFIX + tokenFor(p.udn))
                }
                val info = ShortcutInfoCompat.Builder(context, PREFIX + tokenFor(p.udn))
                    .setShortLabel(p.name)
                    .setLongLabel("推送到 ${p.name}")
                    .setIcon(IconCompat.createWithResource(context, if (p.audioOnly) R.drawable.ic_share_speaker else if (p.ownApp) R.drawable.ic_share_mirror else R.drawable.ic_share_tv))
                    .setCategories(setOf(if (p.audioOnly) CATEGORY_AUDIO else CATEGORY_TV))
                    .setRank(rank)
                    .setIntent(intent)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, info)
            }
            WscLog.i(TAG, "direct share shortcuts: ${picks.size} (removed ${stale.size})")
        }.onFailure { WscLog.w(TAG, "update shortcuts failed", it) }
    }

    data class Pick(val udn: String, val name: String, val audioOnly: Boolean, val ownApp: Boolean = false)

    companion object {
        private const val TAG = "DirectShare"
        private const val PREFIX = "push:"
        const val CATEGORY_TV = "com.benchan.wireless.screencasting.category.SHARE_TO_TV"
        const val CATEGORY_AUDIO = "com.benchan.wireless.screencasting.category.SHARE_TO_SPEAKER"
        const val MAX = 3
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 快捷方式 ID → 令牌（格式不对返回 null）。 */
        fun parseToken(shortcutId: String?): String? =
            shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { t -> t.length in 16..64 && t.all { it.isLetterOrDigit() } }

        /** 选哪几台（纯逻辑，单测覆盖）：30 天内推送成功过、按最近一次成功时间排序、最多 3 台。 */
        fun pick(records: List<DlnaRendererRecord>, now: Long): List<Pick> = records
            .filter { r -> r.confirmedAt != null && r.lastSuccessAt?.let { now - it in 0..WINDOW_MS } == true }
            .sortedByDescending { it.lastSuccessAt }
            .take(MAX)
            .map { Pick(it.udn, it.name, it.audioOnly, ownApp = it.wscId != null) }
    }
}
