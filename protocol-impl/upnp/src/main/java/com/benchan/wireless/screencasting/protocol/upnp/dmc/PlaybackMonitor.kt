package com.benchan.wireless.screencasting.protocol.upnp.dmc

import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import java.net.URLDecoder

/** 一次状态采样后的判断（PRD 8.1.1 D 结束条件、C 连播判定）。 */
enum class MonitorDecision { CONTINUE, FINISHED, STOPPED, TAKEN_OVER }

/**
 * 纯逻辑：根据轮询 / 事件采样判断播完、电视停止、被接管。不做 I/O，时间由调用方传入。
 * - 被接管：TrackURI 非空且不是本会话的地址，连续 2 次。
 * - 播完：停止前位置距总时长 ≤ [PushTimings.finishSlackMs]（加上一个轮询间隔的误差）。
 * - 电视停止：视频 / 音频持续“已停止 / 无媒体”≥ [PushTimings.stopEndMs]。照片不判停止（很多电视没有可靠状态）。
 */
class PlaybackMonitor(
    private val kind: MediaKind,
    private val ownershipKey: String,
    private val remote: Boolean,
    private val timings: PushTimings,
) {
    var lastPositionMs: Long? = null
        private set
    var durationMs: Long? = null
        private set
    private var stoppedSince: Long? = null

    /** 已观察到停止、还没下结论：调用方应加快复查（后台也要在 10 秒内发现停止）。 */
    val stopPending: Boolean get() = stoppedSince != null
    private var foreignCount = 0

    fun onSample(now: Long, state: TransportStateValue?, trackUri: String?, positionMs: Long?, duration: Long?, pollIntervalMs: Long): MonitorDecision {
        if (trackUri != null && !isOurs(trackUri)) {
            if (++foreignCount >= 2) return MonitorDecision.TAKEN_OVER
        } else if (trackUri != null) {
            foreignCount = 0
        }
        if (duration != null && duration > 0) durationMs = duration
        if (positionMs != null && positionMs > 0 && state?.isStopped != true) lastPositionMs = positionMs
        if (kind == MediaKind.IMAGE || state == null) return MonitorDecision.CONTINUE
        if (state.isStopped) {
            val since = stoppedSince ?: now.also { stoppedSince = it }
            if (nearEnd(pollIntervalMs)) return MonitorDecision.FINISHED
            if (now - since >= timings.stopEndMs) return MonitorDecision.STOPPED
        } else if (state != TransportStateValue.UNKNOWN) {
            stoppedSince = null
        }
        return MonitorDecision.CONTINUE
    }

    private fun nearEnd(pollIntervalMs: Long): Boolean {
        val d = durationMs ?: return false
        val p = lastPositionMs ?: return false
        return d - p <= timings.finishSlackMs + (pollIntervalMs - timings.pollForegroundMs).coerceAtLeast(0)
    }

    fun isOurs(trackUri: String): Boolean = isOurs(trackUri, ownershipKey, remote)

    companion object {
        private fun normalizeUrl(u: String): String {
            val t = u.trim().trimEnd('/')
            val scheme = t.substringBefore("://", "")
            if (scheme.isEmpty()) return t
            val rest = t.substringAfter("://")
            val host = rest.substringBefore('/')
            val path = rest.substring(host.length)
            return scheme.lowercase() + "://" + host.lowercase() + path
        }

        fun isOurs(trackUri: String, ownershipKey: String, remote: Boolean): Boolean {
            val decoded = runCatching { URLDecoder.decode(trackUri.trim(), "UTF-8") }.getOrDefault(trackUri.trim())
            return if (remote) {
                // 网址推送：比较完整地址（调用方传 GetMediaInfo.CurrentURI，即我们设置的原始地址，CDN 跳转不影响）
                val key = runCatching { URLDecoder.decode(ownershipKey.trim(), "UTF-8") }.getOrDefault(ownershipKey.trim())
                normalizeUrl(decoded) == normalizeUrl(key)
            } else {
                decoded.contains(ownershipKey, ignoreCase = true)
            }
        }
    }
}
