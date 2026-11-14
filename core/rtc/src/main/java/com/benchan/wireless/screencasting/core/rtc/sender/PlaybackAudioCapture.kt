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

    override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
        val size = bytesRead.coerceAtMost(buffer.capacity())
        if (!enabled) {
            release()
            zeroFill(buffer, size)
            pace(size, sampleRate, channelCount)
            return 0
        }
        val rec = ensureRecord(sampleRate, channelCount)
        if (rec == null) {
            zeroFill(buffer, size)
            pace(size, sampleRate, channelCount)
            return 0
        }
        buffer.position(0)
        val n = rec.read(buffer, size, AudioRecord.READ_BLOCKING)
        if (n < size) {
            // 读取失败或不足：剩余部分补零，防止上一轮数据重复播放
            for (i in maxOf(n, 0) until size) buffer.put(i, 0)
        }
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
                WscLog.w(TAG, "playback capture AudioRecord not initialized")
                return null
            }
            r.startRecording()
            record = r
            recordSampleRate = sampleRate
            recordChannels = channels
            report(AudioState.ON)
            WscLog.i(TAG, "playback capture started ${sampleRate}Hz x$channels")
            r
        } catch (e: Exception) {
            WscLog.w(TAG, "playback capture unavailable", e)
            null
        }
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

    private fun pace(size: Int, sampleRate: Int, channels: Int) {
        val ms = size * 1000L / (sampleRate * channels * 2).coerceAtLeast(1)
        runCatching { Thread.sleep(ms.coerceIn(1, 20)) }
    }

    fun release() {
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    private companion object {
        const val TAG = "PlaybackAudio"
        const val SILENCE_THRESHOLD = 8
        const val SILENT_SUSPECT_MS = 10_000L
    }
}
