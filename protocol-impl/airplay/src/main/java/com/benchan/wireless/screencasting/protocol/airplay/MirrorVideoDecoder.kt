package com.benchan.wireless.screencasting.protocol.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.benchan.wireless.screencasting.core.common.WscLog

/**
 * 镜像视频：H.264 / H.265 Annex-B → MediaCodec（同步模式）→ Surface（技术方案 3.8，不用 GStreamer）。
 *
 * 线程：[submit] 在 UxPlay 镜像线程上调用；[setSurface] / [configure] / [release] 可能来自主线程或其他原生线程，
 * 因此所有 MediaCodec 操作都在 [lock] 内完成。Surface 晚于码流到达时（播放页刚显示），用缓存的参数集重建解码器，
 * 之后的非关键帧可能短暂花屏，直到下一个关键帧。
 */
internal class MirrorVideoDecoder(private val onFirstFrame: (decoderName: String) -> Unit) {
    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var h265 = false
    private var width = 1920
    private var height = 1080
    private var lastConfig: ByteArray? = null
    private var firstFrameReported = false
    private var waitForKeyFrame = true
    private val info = MediaCodec.BufferInfo()

    var decoderName: String? = null
        private set

    fun setSurface(s: Surface?) = synchronized(lock) {
        if (s === surface) return
        surface = s
        releaseCodecLocked()
        if (s != null) startCodecLocked()
    }

    /** UxPlay video_set_codec_and_size：编码格式或分辨率变化。 */
    fun configure(h265: Boolean, width: Int, height: Int) = synchronized(lock) {
        val changed = h265 != this.h265 || codec == null
        this.h265 = h265
        if (width > 0) this.width = width
        if (height > 0) this.height = height
        if (changed) {
            lastConfig = null
            releaseCodecLocked()
            startCodecLocked()
        }
    }

    fun submit(data: ByteArray, ptsUs: Long, h265: Boolean) = synchronized(lock) {
        if (h265 != this.h265) {
            this.h265 = h265
            lastConfig = null
            releaseCodecLocked()
            startCodecLocked()
        }
        val cfgLen = AnnexB.codecConfigLength(data, h265)
        if (cfgLen > 0) lastConfig = data.copyOfRange(0, cfgLen)
        val d = codec ?: return
        val keyFrame = AnnexB.containsKeyFrame(data, h265)
        if (waitForKeyFrame && !keyFrame && cfgLen == 0) return
        try {
            if (cfgLen > 0) queue(d, data, 0, cfgLen, ptsUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            if (cfgLen < data.size) {
                queue(d, data, cfgLen, data.size - cfgLen, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                if (keyFrame) waitForKeyFrame = false
            }
            drain(d)
        } catch (e: IllegalStateException) {
            WscLog.w(TAG, "decoder error, restarting", e)
            releaseCodecLocked()
            startCodecLocked()
        }
    }

    /** UxPlay video_flush：丢弃解码器中排队的帧。 */
    fun flush() = synchronized(lock) {
        runCatching { codec?.flush() }
        waitForKeyFrame = lastConfig == null
    }

    fun release() = synchronized(lock) {
        releaseCodecLocked()
        lastConfig = null
        firstFrameReported = false
    }

    private fun queue(d: MediaCodec, src: ByteArray, off: Int, len: Int, ptsUs: Long, flags: Int) {
        val idx = d.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (idx < 0) {
            WscLog.d(TAG, "input buffer unavailable, frame dropped")
            return
        }
        val buf = d.getInputBuffer(idx) ?: return
        buf.clear()
        if (len > buf.capacity()) {
            d.queueInputBuffer(idx, 0, 0, ptsUs, 0)
            WscLog.w(TAG, "frame larger than input buffer (${len}B), dropped")
            waitForKeyFrame = true
            return
        }
        buf.put(src, off, len)
        d.queueInputBuffer(idx, 0, len, ptsUs, flags)
    }

    private fun drain(d: MediaCodec) {
        while (true) {
            val out = d.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    // 低延迟：解出即渲染，不按 PTS 排队（镜像与音频不做严格同步，见开发进度 F15 备注）
                    d.releaseOutputBuffer(out, true)
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        onFirstFrame(decoderName.orEmpty())
                    }
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> return
            }
        }
    }

    private fun startCodecLocked() {
        val s = surface ?: return
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        runCatching {
            val c = MediaCodec.createDecoderByType(mime)
            try {
                val fmt = MediaFormat.createVideoFormat(mime, width.coerceAtLeast(16), height.coerceAtLeast(16))
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                c.configure(fmt, s, null, 0)
                c.start()
            } catch (t: Throwable) {
                c.release()
                throw t
            }
            codec = c
            decoderName = c.name
            // 有缓存的参数集时不等关键帧（iPhone 静止画面可能很久才发下一个 IDR，宁可短暂花屏也不要长时间黑屏）
            waitForKeyFrame = lastConfig == null
            // Surface 晚到：先补送缓存的参数集
            lastConfig?.let { cfg -> queue(c, cfg, 0, cfg.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
            WscLog.i(TAG, "decoder ${c.name} started ($mime ${width}x$height)")
        }.onFailure { WscLog.w(TAG, "decoder init failed ($mime)", it) }
    }

    private fun releaseCodecLocked() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    private companion object {
        const val TAG = "AppleMirror"
        const val INPUT_TIMEOUT_US = 20_000L
        const val MAX_INPUT_SIZE = 4 * 1024 * 1024
    }
}
