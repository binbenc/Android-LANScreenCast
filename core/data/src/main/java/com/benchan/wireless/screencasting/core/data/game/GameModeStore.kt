package com.benchan.wireless.screencasting.core.data.game

import android.content.Context
import com.benchan.wireless.screencasting.core.data.JsonFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 某台接收端上游戏模式的实测结果（交互稿 15 层级 ⑤“记住结果”）。只存本机。 */
@Serializable
data class ReceiverGameMemory(
    /** 上次游戏模式的估算延迟中位数（ms）。 */
    val latencyP50Ms: Int? = null,
    /** 这台接收端跟不上 60 帧：下次直接用 30 帧。 */
    val prefer30Fps: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class GameModeMemory(
    /** G01 首次说明卡是否已确认（本机一生一次）。 */
    val introAcknowledged: Boolean = false,
    /** 接收端 deviceId → 实测结果。 */
    val receivers: Map<String, ReceiverGameMemory> = emptyMap(),
)

/**
 * F18 游戏模式的本机记忆（独立文件，不改动通用设置数据）：首次说明卡、每台接收端的实测延迟与 30 帧偏好。
 */
@Singleton
class GameModeStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFileStore(File(context.noBackupFilesDir, "game_mode.json"), GameModeMemory.serializer()) { GameModeMemory() }

    private val _memory = MutableStateFlow(store.read())
    val memory: StateFlow<GameModeMemory> = _memory.asStateFlow()

    val introAcknowledged: Boolean get() = _memory.value.introAcknowledged

    fun acknowledgeIntro() {
        if (introAcknowledged) return
        _memory.value = store.update { it.copy(introAcknowledged = true) }
    }

    fun receiver(deviceId: String?): ReceiverGameMemory? = deviceId?.let { _memory.value.receivers[it] }

    /** 合并写入（null 字段保持原值）。最多保留 [MAX] 台。 */
    fun recordReceiver(deviceId: String, latencyP50Ms: Int? = null, prefer30Fps: Boolean? = null, now: Long = System.currentTimeMillis()) {
        _memory.value = store.update { m ->
            val old = m.receivers[deviceId] ?: ReceiverGameMemory()
            val next = old.copy(latencyP50Ms = latencyP50Ms ?: old.latencyP50Ms, prefer30Fps = prefer30Fps ?: old.prefer30Fps, updatedAt = now)
            val all = (m.receivers - deviceId).entries.sortedByDescending { it.value.updatedAt }.take(MAX - 1).associate { it.key to it.value }
            m.copy(receivers = all + (deviceId to next))
        }
    }

    /** 移除一台接收端的记忆（移除信任设备时）。 */
    fun removeReceiver(deviceId: String) {
        if (deviceId !in _memory.value.receivers) return
        _memory.value = store.update { it.copy(receivers = it.receivers - deviceId) }
    }

    /** 清掉所有接收端的记忆（清空报告时）；首次说明卡的确认保留（那是用户对取舍的确认，不是统计）。 */
    fun clearReceivers() {
        _memory.value = store.update { it.copy(receivers = emptyMap()) }
    }

    private companion object {
        const val MAX = 20
    }
}
