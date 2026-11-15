package com.benchan.wireless.screencasting.protocol.dlna.dmc

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.benchan.wireless.screencasting.core.common.WscLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** 转换参数（PRD 8.1.1 F2：H.264 + AAC-LC 双声道 MP4，≤1080p，帧率 ≤60，HDR → SDR）。 */
data class TranscodeSpec(
    val removeAudio: Boolean = false,
    val sourceShortSide: Int? = null,
    val sourceFrameRate: Int? = null,
    val sourceAudioChannels: Int? = null,
    val durationMs: Long? = null,
) {
    val outputShortSide: Int get() = minOf(sourceShortSide ?: MAX_SHORT_SIDE, MAX_SHORT_SIDE)

    /** 目标视频码率：1080p 8 Mbps、720p 5 Mbps、更低 3 Mbps。 */
    val videoBitrate: Int
        get() = when {
            outputShortSide >= 1000 -> 8_000_000
            outputShortSide >= 700 -> 5_000_000
            else -> 3_000_000
        }

    /** PRD F2：需要的空间 = 目标码率 × 时长 × 1.1。 */
    fun estimatedBytes(): Long? {
        val d = durationMs ?: return null
        val bps = videoBitrate + if (removeAudio) 0 else AUDIO_BITRATE
        return (bps.toLong() * d / 8_000 * 11 / 10)
    }

    /** 报告里的输出参数。 */
    val outputLabel: String get() = "h264/${outputShortSide}p/${videoBitrate / 1_000_000}M" + if (removeAudio) "/noaudio" else "/aac"

    companion object {
        const val MAX_SHORT_SIDE = 1080
        const val AUDIO_BITRATE = 192_000
    }
}

sealed interface TranscodeResult {
    data class Success(val file: File, val elapsedMs: Long, val width: Int, val height: Int, val thermalPauses: Int) : TranscodeResult
    data class Failed(val errorCode: Int?, val thermalPauses: Int) : TranscodeResult
}

/** 空间检查（PRD F2“空间不足”）。[requiredBytes] 按 PRD 公式显示；判断时另加 [reserveBytes] 余量（第 1 轮 PM P2-10）。 */
data class SpaceCheck(val requiredBytes: Long, val availableBytes: Long, val reserveBytes: Long = 0) {
    val enough: Boolean get() = availableBytes >= requiredBytes + reserveBytes
}

/**
 * 视频“转换后推送”（PRD 8.1.1 F2、技术方案 3.13.4.7）：Media3 Transformer（系统 MediaCodec 硬件编解码，不引入 FFmpeg，不访问网络）。
 * - 进度通过 [onProgress]（0~100）回调；协程取消 = 用户取消：立刻 `cancel()` 并删除临时文件。
 * - [paused] 为 true（手机过热）时取消当前导出并保留已写部分，降温后用 `Transformer.resume()` 从上一个关键帧继续；
 *   resume 失败时从头再来。
 */
@OptIn(UnstableApi::class)
class VideoTranscoder(private val context: Context) {

    fun checkSpace(dir: File, spec: TranscodeSpec, fallbackSourceBytes: Long?): SpaceCheck {
        dir.mkdirs()
        val need = spec.estimatedBytes() ?: (fallbackSourceBytes?.let { it * 11 / 10 } ?: 0L)
        val avail = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
        // 额外留 200 MB 余量，避免把手机空间吃光
        return SpaceCheck(need, avail, RESERVE_BYTES)
    }

    suspend fun transcode(uri: Uri, out: File, spec: TranscodeSpec, paused: StateFlow<Boolean>, onProgress: (Int) -> Unit): TranscodeResult {
        val started = System.currentTimeMillis()
        out.parentFile?.mkdirs()
        var thermalPauses = 0
        var previous: File? = null
        var attempt = 0
        val composition = buildComposition(uri, spec)
        try {
            while (true) {
                val target = if (attempt == 0) out else File(out.parentFile, "${out.nameWithoutExtension}.r$attempt.mp4")
                val outcome = runOnce(composition, spec.videoBitrate, target, previous, paused, onProgress)
                when (outcome) {
                    is Once.Done -> {
                        if (target != out) {
                            out.delete()
                            if (!target.renameTo(out)) target.copyTo(out, overwrite = true).also { target.delete() }
                        }
                        previous?.delete()
                        return TranscodeResult.Success(out, System.currentTimeMillis() - started, outcome.width, outcome.height, thermalPauses)
                    }
                    is Once.Error -> {
                        if (previous != null) {
                            // resume 失败：从头再来一次
                            WscLog.w(TAG, "resume failed (${outcome.code}), restarting")
                            previous.delete()
                            previous = null
                            target.delete()
                            attempt++
                            continue
                        }
                        target.delete()
                        return TranscodeResult.Failed(outcome.code, thermalPauses)
                    }
                    Once.Paused -> {
                        thermalPauses++
                        WscLog.i(TAG, "transcode paused (thermal)")
                        paused.first { !it }
                        previous?.let { if (it != target) it.delete() }
                        previous = target
                        attempt++
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            out.parentFile?.listFiles { f -> f.name.startsWith(out.nameWithoutExtension) }?.forEach { it.delete() }
            throw e
        }
    }

    private sealed interface Once {
        data class Done(val width: Int, val height: Int) : Once
        data class Error(val code: Int?) : Once
        data object Paused : Once
    }

    private suspend fun runOnce(composition: Composition, bitrate: Int, target: File, previous: File?, paused: StateFlow<Boolean>, onProgress: (Int) -> Unit): Once =
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Once>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true)
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Once.Done(exportResult.width, exportResult.height))
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        WscLog.w(TAG, "transcode error ${exportException.errorCode} ${exportException.errorCodeName}")
                        done.complete(Once.Error(exportException.errorCode))
                    }
                })
                .build()
            try {
                target.delete()
                if (previous != null) transformer.resume(composition, target.absolutePath, previous.absolutePath)
                else transformer.start(composition, target.absolutePath)
                coroutineScope {
                    val watcher = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            if (paused.value) {
                                transformer.cancel()
                                done.complete(Once.Paused)
                                break
                            }
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                            delay(PROGRESS_MS)
                        }
                    }
                    val r = done.await()
                    watcher.cancel()
                    r
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                transformer.cancel()
                throw e
            }
        }

    private fun buildComposition(uri: Uri, spec: TranscodeSpec): Composition {
        val videoEffects = buildList {
            if ((spec.sourceShortSide ?: 0) > TranscodeSpec.MAX_SHORT_SIDE) add(Presentation.createForShortSide(TranscodeSpec.MAX_SHORT_SIDE))
            if ((spec.sourceFrameRate ?: 0) > MAX_FPS) add(FrameDropEffect.createDefaultFrameDropEffect(MAX_FPS.toFloat()))
        }
        val audioProcessors = buildList<AudioProcessor> {
            val ch = spec.sourceAudioChannels ?: 2
            if (!spec.removeAudio && ch > 2) {
                val mixer = ChannelMixingAudioProcessor()
                for (n in 1..8) runCatching { mixer.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(n, 2)) }
                add(mixer)
            }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setRemoveAudio(spec.removeAudio)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
        return Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private companion object {
        const val TAG = "VideoTranscoder"
        const val PROGRESS_MS = 500L
        const val MAX_FPS = 60
        const val RESERVE_BYTES = 200L * 1024 * 1024
    }
}
