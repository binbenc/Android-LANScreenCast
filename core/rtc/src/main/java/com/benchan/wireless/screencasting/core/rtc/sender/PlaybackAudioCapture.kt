package com.benchan.wireless.screencasting.core.rtc.sender

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * 内录声音（F02、PRD S-03）：AudioPlaybackCapture → libwebrtc 音频采集，不打开麦克风。
 *
 * 实现方式（与技术方案 3.3 的差异，见 docs/dev/开发进度.md）：webrtc-sdk 150.7871.04 预编译包已带
 * `JavaAudioDeviceModule.setAudioRecordEnabled(false)` + `AudioBufferCallback`：关闭内置 AudioRecord（不会创建麦克风
 * AudioRecord）后，采集线程每 10ms 回调一次，由我们把 AudioPlaybackCapture 读到的 PCM 填进缓冲。
 * 回调必须阻塞到读满一个缓冲，否则采集线程会空转——这里用 READ_BLOCKING 的 AudioRecord.read 天然限速。
 *
 * TODO(M1 自建 fork P1 补丁)：换成 Builder.setAudioPlaybackCaptureConfiguration(cfg)，去掉这层回调；
 *  并在真机确认：①不出现麦克风隐私指示灯；②后台持续有声；③音画同步在 PRD 1.3 阈值内。
 */
class PlaybackAudioCapture(
    private val projectionProvider: () -> MediaProjection?,
    private val onState: (AudioState) -> Unit,
) : JavaAudioDeviceModule.AudioBufferCallback {
    @Volatile
    var enabled: Boolean = true

    private var record: AudioRecord? = null
    private var recordSampleRate = 0
    private var recordChannels = 0
    private var silentMs = 0L
    private var lastReported: AudioState? = null
    private var consecutiveFailures = 0
    private var retryAfterNs = 0L
    private var initFailures = 0

    /** 连续读失败（约 1 秒）时上报“疑似无声”，界面提示而不是静默。 */
    private fun onReadFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= FAILURES_BEFORE_SILENT) report(AudioState.SILENT_SUSPECTED)
    }

    /** 下一个缓冲“应当”交付的时刻（单调时钟）；把采集回调限制在实时速率。 */
    private var nextDueNs = 0L

    override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
        val size = bytesRead.coerceAtMost(buffer.capacity())
        try {
            return fill(buffer, size, sampleRate, channelCount)
        } finally {
            // 无论 read 是否阻塞，都按缓冲时长限速到实时：部分系统（如 API 33 模拟器）上回放捕获在没有声音时
            // read 立即返回，采集线程会以远超实时的速度向 libwebrtc 推送音频帧，几秒内耗尽原生内存（Scudo NO MEMORY）。
            keepRealtime(size, sampleRate, channelCount)
        }
    }

    private fun fill(buffer: ByteBuffer, size: Int, sampleRate: Int, channelCount: Int): Long {
        if (!enabled) {
            release()
            zeroFill(buffer, size)
            return 0
        }
        val rec = if (System.nanoTime() < retryAfterNs) null else ensureRecord(sampleRate, channelCount)
        if (rec == null) {
            zeroFill(buffer, size)
            onReadFailure()
            return 0
        }
        buffer.position(0)
        val n = rec.read(buffer, size, AudioRecord.READ_BLOCKING)
        if (n < size) {
            // 读取失败或不足：剩余部分补零，防止上一轮数据重复播放（限速由 keepRealtime 统一处理，E5）
            for (i in maxOf(n, 0) until size) buffer.put(i, 0)
        }
        if (n < 0) {
            if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                // AudioRecord 已失效（例如音频服务重启）：释放，稍后重建
                WscLog.w(TAG, "playback capture dead object, will recreate")
                release()
                retryAfterNs = System.nanoTime() + RETRY_DELAY_NS
            }
            onReadFailure()
            return 0
        }
        consecutiveFailures = 0
        trackSilence(buffer, size, sampleRate, channelCount)
        return 0 // 0 = 使用采集线程自己的时间戳
    }

    @SuppressLint("MissingPermission") // 调用方已确认 RECORD_AUDIO 授权；未授权时不会启用声音
    private fun ensureRecord(sampleRate: Int, channels: Int): AudioRecord? {
        record?.let { if (recordSampleRate == sampleRate && recordChannels == channels) return it }
        release()
        val projection = projectionProvider() ?: return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate * channels * 2 / 10))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                onInitFailure("AudioRecord not initialized")
                return null
            }
            r.startRecording()
            initFailures = 0
            record = r
            recordSampleRate = sampleRate
            recordChannels = channels
            report(AudioState.ON)
            WscLog.i(TAG, "playback capture started ${sampleRate}Hz x$channels")
            r
        } catch (e: Exception) {
            onInitFailure("unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 创建失败（例如系统拒绝录音 app-op、被投 App 禁止捕获）时指数退避重试。
     * 每次创建都会向系统注册一个 AudioPolicy（Binder 对象）；此前每 10ms 重试一次，约 1 分钟后
     * system_server 以 "sent too many Binders" 杀掉整个进程（投屏中断）。日志也只在退避时记一次。
     */
    private fun onInitFailure(reason: String) {
        initFailures++
        val delayNs = initRetryDelayNs(initFailures)
        retryAfterNs = System.nanoTime() + delayNs
        WscLog.w(TAG, "playback capture $reason (attempt $initFailures), retry in ${delayNs / 1_000_000}ms")
    }

    private fun trackSilence(buffer: ByteBuffer, size: Int, sampleRate: Int, channels: Int) {
        var silent = true
        var i = 0
        while (i + 1 < size) {
            val s = (buffer.get(i).toInt() and 0xFF) or (buffer.get(i + 1).toInt() shl 8)
            if (s > SILENCE_THRESHOLD || s < -SILENCE_THRESHOLD) {
                silent = false
                break
            }
            i += 2
        }
        val durMs = size * 1000L / (sampleRate * channels * 2).coerceAtLeast(1)
        silentMs = if (silent) silentMs + durMs else 0
        // 连续 10 秒静音：可能是被投 App 禁止捕获（ALLOW_CAPTURE_BY_NONE），也可能本来就没声音，只作提示
        report(if (silentMs >= SILENT_SUSPECT_MS) AudioState.SILENT_SUSPECTED else AudioState.ON)
    }

    private fun report(state: AudioState) {
        if (state != lastReported) {
            lastReported = state
            onState(state)
        }
    }

    private fun zeroFill(buffer: ByteBuffer, size: Int) {
        for (i in 0 until size) buffer.put(i, 0)
    }

    private fun keepRealtime(bytes: Int, sampleRate: Int, channels: Int) {
        val now = System.nanoTime()
        val (due, waitNs) = realtimeStep(nextDueNs, now, bufferDurationNs(bytes, sampleRate, channels))
        nextDueNs = due
        if (waitNs > 0) runCatching { Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt()) }
    }

    fun release() {
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    companion object {
        private const val TAG = "PlaybackAudio"
        private const val SILENCE_THRESHOLD = 8
        private const val SILENT_SUSPECT_MS = 10_000L
        private const val FAILURES_BEFORE_SILENT = 100 // 100 × 10ms ≈ 1 秒
        private const val RETRY_DELAY_NS = 500_000_000L

        private const val INIT_RETRY_BASE_NS = 500_000_000L
        private const val INIT_RETRY_MAX_NS = 10_000_000_000L

        /** 第 [failures] 次创建失败后的重试间隔：0.5s、1s、2s… 封顶 10s。 */
        internal fun initRetryDelayNs(failures: Int): Long =
            (INIT_RETRY_BASE_NS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(INIT_RETRY_MAX_NS)

        /** 落后超过这么多就不再追赶（避免卡顿后连续不睡、突发推送）。 */
        private const val MAX_LAG_NS = 100_000_000L

        /** 16-bit PCM 的 [bytes] 字节对应的时长；至少 1ms，最多 20ms（采集回调周期 10ms，绝不空转）。 */
        internal fun bufferDurationNs(bytes: Int, sampleRate: Int, channels: Int): Long =
            (bytes.coerceAtLeast(0).toLong() * 1_000_000_000L / (sampleRate * channels * 2).coerceAtLeast(1))
                .coerceIn(1_000_000L, 20_000_000L)

        /**
         * 实时限速的一步：返回（新的下一交付时刻, 需要睡眠的纳秒）。首次或落后超过 [MAX_LAG_NS] 时从当前时刻重新计时。
         * read 真正阻塞时等待为 0 或很小，不影响正常路径。
         */
        internal fun realtimeStep(prevDueNs: Long, nowNs: Long, durationNs: Long): Pair<Long, Long> {
            val base = if (prevDueNs == 0L || nowNs - prevDueNs > MAX_LAG_NS) nowNs else prevDueNs
            val due = base + durationNs
            return due to (due - nowNs).coerceAtLeast(0L)
        }
    }
}
