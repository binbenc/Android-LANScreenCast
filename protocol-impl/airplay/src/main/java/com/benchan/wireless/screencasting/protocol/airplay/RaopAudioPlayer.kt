package com.benchan.wireless.screencasting.protocol.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.benchan.wireless.screencasting.core.common.WscLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RAOP 音频输出（技术方案 3.8）：ALAC 已在原生层解码为 PCM；AAC-LC / AAC-ELD 用 MediaCodec 解码；统一写入 AudioTrack。
 *
 * 线程：所有方法都在 UxPlay 的 RTP 音频线程（或 httpd 线程）上调用，用 [lock] 串行化。
 * AudioTrack 用非阻塞写入：缓冲区满时丢弃（不阻塞 UxPlay 的接收线程），以低延迟优先；不做与视频的严格同步。
 */
internal class RaopAudioPlayer {
    private val lock = Any()
    private var codec = RaopAudioCodec.ALAC
    private var track: AudioTrack? = null
    private var aac: MediaCodec? = null
    private var volume = 1f
    private var dropped = 0L
    private val info = MediaCodec.BufferInfo()

    fun setFormat(ct: Int) = synchronized(lock) {
        val c = RaopAudioCodec.fromCt(ct)
        if (c == codec && (track != null || aac != null)) return
        releaseLocked()
        codec = c
        WscLog.i(TAG, "audio format $c")
        if (c.mime != null) {
            aac = runCatching {
                MediaCodec.createDecoderByType(c.mime).apply {
                    val fmt = MediaFormat.createAudioFormat(c.mime, RaopAudioCodec.SAMPLE_RATE, RaopAudioCodec.CHANNELS)
                    fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(c.csd0!!))
                    if (c == RaopAudioCodec.AAC_ELD) fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_OBJECT_ELD)
                    configure(fmt, null, null, 0)
                    start()
                }
            }.onFailure { WscLog.w(TAG, "AAC decoder ($c) unavailable", it) }.getOrNull()
        }
    }

    fun writePcm(pcm: ShortArray, count: Int) = synchronized(lock) {
        val t = ensureTrackLocked() ?: return
        val n = t.write(pcm, 0, count, AudioTrack.WRITE_NON_BLOCKING)
        if (n in 0 until count) countDrop(count - n)
    }

    fun writeCompressed(data: ByteArray, ct: Int) = synchronized(lock) {
        if (RaopAudioCodec.fromCt(ct) != codec) setFormat(ct)
        if (codec == RaopAudioCodec.PCM) {
            // 与 UxPlay audio_renderer 一致按 S16LE 处理
            val shorts = ShortArray(data.size / 2)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            writePcm(shorts, shorts.size)
            return
        }
        val d = aac ?: return
        try {
            val idx = d.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (idx >= 0) {
                val buf = d.getInputBuffer(idx)
                val len = if (buf != null && data.size <= buf.capacity()) data.size else 0
                buf?.clear()
                if (len > 0) buf?.put(data, 0, len)
                d.queueInputBuffer(idx, 0, len, 0, 0)
            } else {
                countDrop(codec.samplesPerFrame * RaopAudioCodec.CHANNELS)
            }
            while (true) {
                val out = d.dequeueOutputBuffer(info, 0)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (out < 0) break
                val buf = d.getOutputBuffer(out)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    val t = ensureTrackLocked()
                    if (t != null) {
                        val n = t.write(buf, info.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (n in 0 until info.size) countDrop((info.size - n) / 2)
                    }
                }
                d.releaseOutputBuffer(out, false)
            }
        } catch (e: IllegalStateException) {
            WscLog.w(TAG, "AAC decode error", e)
            val c = codec.ct
            releaseLocked()
            setFormat(c)
        }
    }

    fun setVolumeDb(db: Float) = synchronized(lock) {
        volume = AirPlayVolume.toLinear(db)
        track?.setVolume(volume)
    }

    fun flush() = synchronized(lock) {
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        runCatching { aac?.flush() }
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun ensureTrackLocked(): AudioTrack? {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(RaopAudioCodec.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RaopAudioCodec.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                // 约 4 倍最小缓冲（~150ms）：吸收 Wi‑Fi 抖动，又不至于让延迟过大
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
                .also {
                    it.setVolume(volume)
                    it.play()
                }
        }.onFailure { WscLog.w(TAG, "AudioTrack init failed", it) }.getOrNull().also { track = it }
    }

    private fun countDrop(samples: Int) {
        dropped += samples
        if (dropped >= RaopAudioCodec.SAMPLE_RATE * RaopAudioCodec.CHANNELS) {
            WscLog.d(TAG, "audio buffer full, ~1s of samples dropped")
            dropped = 0
        }
    }

    private fun releaseLocked() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        aac?.let { runCatching { it.stop() }; runCatching { it.release() } }
        aac = null
    }

    private companion object {
        const val TAG = "AppleAudio"
        const val INPUT_TIMEOUT_US = 10_000L
        const val AAC_OBJECT_ELD = 39 // MediaCodecInfo.CodecProfileLevel.AACObjectELD
    }
}
