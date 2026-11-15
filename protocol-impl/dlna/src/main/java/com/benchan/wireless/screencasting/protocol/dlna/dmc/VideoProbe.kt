package com.benchan.wireless.screencasting.protocol.dlna.dmc

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.benchan.wireless.screencasting.protocol.upnp.MediaMime
import com.benchan.wireless.screencasting.protocol.upnp.VideoFormatInfo

/** 本地视频 / 音频的格式探测（推送前判断是否提议转换、转换前检查手机能否解码，PRD 8.1.1 F2）。 */
data class MediaProbe(
    val format: VideoFormatInfo,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val frameRate: Int?,
    val audioChannels: Int?,
    /** 手机有对应的视频 / 音频解码器（转换的前提）。 */
    val videoDecodable: Boolean,
    val audioDecodable: Boolean,
) {
    /** “1920x1080”（旋转后）。 */
    val resolution: String? get() = if (width != null && height != null) "${width}x$height" else null

    /** 报告用的分辨率档位。 */
    val resolutionBucket: String?
        get() {
            val short = listOfNotNull(width, height).minOrNull() ?: return null
            return when {
                short <= 720 -> "<=720p"
                short <= 1080 -> "1080p"
                else -> "4K"
            }
        }
}

object VideoProbeReader {
    fun probe(context: Context, uri: Uri, containerMime: String): MediaProbe? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video == null && mime.startsWith("video/")) video = f
                if (audio == null && mime.startsWith("audio/")) audio = f
            }
            val vMime = video?.getString(MediaFormat.KEY_MIME)
            val aMime = audio?.getString(MediaFormat.KEY_MIME)
            val transfer = video?.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                vMime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
            val rotation = video?.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
            var w = video?.intOrNull(MediaFormat.KEY_WIDTH)
            var h = video?.intOrNull(MediaFormat.KEY_HEIGHT)
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val durUs = listOfNotNull(video?.longOrNull(MediaFormat.KEY_DURATION), audio?.longOrNull(MediaFormat.KEY_DURATION)).maxOrNull()
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            MediaProbe(
                format = VideoFormatInfo(MediaMime.normalize(containerMime), vMime, aMime, hdr, w, h),
                durationMs = durUs?.let { it / 1000 },
                width = w,
                height = h,
                frameRate = video?.intOrNull(MediaFormat.KEY_FRAME_RATE),
                audioChannels = audio?.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                videoDecodable = video?.let { decodable(codecs, it) } ?: false,
                audioDecodable = audio?.let { decodable(codecs, it) } ?: true,
            )
        } finally {
            ex.release()
        }
    }.getOrNull()

    private fun decodable(codecs: MediaCodecList, f: MediaFormat): Boolean {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: return false
        val probe = if (mime.startsWith("video/")) {
            MediaFormat.createVideoFormat(mime, f.intOrNull(MediaFormat.KEY_WIDTH) ?: 1280, f.intOrNull(MediaFormat.KEY_HEIGHT) ?: 720).apply {
                f.intOrNull(MediaFormat.KEY_PROFILE)?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            }
        } else {
            MediaFormat.createAudioFormat(mime, f.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 48_000, f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2)
        }
        return runCatching { codecs.findDecoderForFormat(probe) != null }.getOrDefault(false)
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
}
