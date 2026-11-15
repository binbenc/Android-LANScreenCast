package com.benchan.wireless.screencasting.core.data.dlna

import android.content.Context
import com.benchan.wireless.screencasting.core.data.JsonFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 推送过的 DLNA 电视（PRD 8.1.1，决策 #26~#32）：按 UDN 记住
 * - 首次推送确认（D13-3，确认过就不再问）；
 * - 每台电视试出来的降级方式（F：元数据档位、先 Stop、Seek 单位）；
 * - 格式记忆（F2：这台电视对某种容器 / 编码失败过 → 下次推送前提议转换；直接推原文件成功过 → 不再提议）；
 * - 上次的设备描述地址（P1“最近推送过的电视”直接探测）。
 * 只存本机 noBackupFilesDir。
 */
@Serializable
data class DlnaRendererRecord(
    val udn: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val location: String? = null,
    val confirmedAt: Long? = null,
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,
    val quirks: Map<String, String> = emptyMap(),
    val failedFormats: Set<String> = emptySet(),
    val okFormats: Set<String> = emptySet(),
    /** 上次推送成功（电视开始播放 / 取走照片）的时间；F14 Direct Share 只放 30 天内成功过的设备（PRD 8.1.2 H）。 */
    val lastSuccessAt: Long? = null,
    /** 推送成功时这台设备是否只能放音乐（Direct Share 的音箱只匹配音乐）。 */
    val audioOnly: Boolean = false,
    /** 本应用接收端的 WSC deviceId（设备描述里的 wsc:id）：长辈向导把它和镜像身份合并成同一台（EMU-F1）。 */
    val wscId: String? = null,
)

@Singleton
class DlnaRendererStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFileStore(
        File(context.noBackupFilesDir, "dlna_renderers.json"),
        ListSerializer(DlnaRendererRecord.serializer()),
    ) { emptyList() }

    private val _records = MutableStateFlow(store.read().sortedByDescending { it.lastUsedAt })
    val records: StateFlow<List<DlnaRendererRecord>> = _records.asStateFlow()

    fun find(udn: String): DlnaRendererRecord? = _records.value.firstOrNull { it.udn == udn }

    fun isConfirmed(udn: String): Boolean = find(udn)?.confirmedAt != null

    fun confirm(udn: String, name: String, manufacturer: String?, model: String?, location: String?, now: Long = System.currentTimeMillis()) =
        upsert(udn, name) { it.copy(confirmedAt = it.confirmedAt ?: now, manufacturer = manufacturer, model = model, location = location ?: it.location) }

    /** 一次推送开始：更新最近使用与描述地址。 */
    fun recordUse(udn: String, name: String, manufacturer: String?, model: String?, location: String?, now: Long = System.currentTimeMillis(), wscId: String? = null) =
        upsert(udn, name) { it.copy(lastUsedAt = now, useCount = it.useCount + 1, manufacturer = manufacturer, model = model, location = location ?: it.location, wscId = wscId ?: it.wscId) }

    /** 一次推送成功开始播放（F14 Direct Share 排序依据）。 */
    fun recordSuccess(udn: String, audioOnly: Boolean, now: Long = System.currentTimeMillis()) =
        update(udn) { it.copy(lastSuccessAt = now, audioOnly = audioOnly) }

    /**
     * 发现时看到同一 UDN 的新描述地址（DMR 端口变了）：只更新已记录的设备，地址相同或没有记录时什么都不做。
     * 不改最近使用时间，不新增记录。
     */
    fun updateLocation(udn: String, location: String?) {
        if (location.isNullOrBlank()) return
        val old = find(udn) ?: return
        if (old.location == location) return
        update(udn) { it.copy(location = location) }
    }

    fun saveQuirks(udn: String, quirks: Map<String, String>) = update(udn) { it.copy(quirks = quirks) }

    fun recordFormat(udn: String, key: String, ok: Boolean) = update(udn) {
        if (ok) it.copy(okFormats = it.okFormats + key, failedFormats = it.failedFormats - key)
        else it.copy(failedFormats = it.failedFormats + key, okFormats = it.okFormats - key)
    }

    /** “忘记这台设备”（设备详情 / 可信设备页“推送过的电视”分组）。 */
    fun forget(udn: String) {
        _records.value = store.update { list -> list.filterNot { it.udn == udn } }
    }

    fun clear() {
        store.clear()
        _records.value = emptyList()
    }

    private fun update(udn: String, block: (DlnaRendererRecord) -> DlnaRendererRecord) {
        if (find(udn) == null) return
        _records.value = store.update { list -> list.map { if (it.udn == udn) block(it) else it } }
    }

    private fun upsert(udn: String, name: String, block: (DlnaRendererRecord) -> DlnaRendererRecord) {
        _records.value = store.update { list ->
            val old = list.firstOrNull { it.udn == udn } ?: DlnaRendererRecord(udn = udn, name = name)
            (listOf(block(old.copy(name = name))) + list.filterNot { it.udn == udn })
                .sortedByDescending { it.lastUsedAt }
                .take(MAX)
        }
    }

    private companion object {
        const val MAX = 30
    }
}

/** 本机 DMR 的 UDN（接收端 DLNA 与发送端“排除本机”共用同一个文件）。 */
@Singleton
class LocalDlnaIdentity @Inject constructor(@ApplicationContext context: Context) {
    val udn: String by lazy {
        val f = File(context.noBackupFilesDir, "dlna_udn")
        runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { runCatching { f.writeText(it) } }
    }
}

/** 本机转码速度（转换后推送的时长估算用，PRD F2 / 交互稿 14-E：时长 = 视频时长 × 系数）。 */
@Singleton
class TranscodeSpeedStore @Inject constructor(@ApplicationContext context: Context) {
    @Serializable
    data class Speed(val factor: Double = DEFAULT_FACTOR, val samples: Int = 0)

    private val store = JsonFileStore(File(context.noBackupFilesDir, "transcode_speed.json"), Speed.serializer()) { Speed() }

    /** 转换耗时 / 视频时长（越小越快）。 */
    fun factor(): Double = store.read().factor

    fun record(elapsedMs: Long, mediaDurationMs: Long) {
        if (elapsedMs <= 0 || mediaDurationMs <= 5_000) return
        val sample = (elapsedMs.toDouble() / mediaDurationMs).coerceIn(0.05, 5.0)
        store.update { s ->
            val n = (s.samples + 1).coerceAtMost(10)
            Speed(factor = s.factor + (sample - s.factor) / n, samples = n)
        }
    }

    companion object {
        /** 没有样本时假设转换耗时约为视频时长的 0.6 倍（中端机 1080p H.264 硬编，[推断]）。 */
        const val DEFAULT_FACTOR = 0.6
    }
}
