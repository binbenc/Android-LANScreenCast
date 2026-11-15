package com.benchan.wireless.screencasting.core.rtc.sender

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.quality.Degradation
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import org.webrtc.RtpParameters
import org.webrtc.RtpSender

/**
 * 档位 → 发送参数（技术方案 3.2）。setParameters 必须基于 getParameters() 返回的对象修改（带 transactionId）。
 */
object QualityApplier {
    /** @param fps 实际帧率上限（游戏模式降帧时低于档位帧率）。 */
    fun apply(sender: RtpSender, q: QualityProfile, fps: Int = q.fps): Boolean {
        val p = sender.parameters
        p.degradationPreference = when (q.degradation) {
            Degradation.MAINTAIN_FRAMERATE -> RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            Degradation.MAINTAIN_RESOLUTION -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            Degradation.BALANCED -> RtpParameters.DegradationPreference.BALANCED
        }
        p.encodings.firstOrNull()?.apply {
            maxBitrateBps = q.maxBitrateBps
            minBitrateBps = q.minBitrateBps
            maxFramerate = fps
            scaleResolutionDownBy = 1.0
            networkPriority = org.webrtc.Priority.HIGH
        }
        val ok = sender.setParameters(p)
        if (!ok) WscLog.w("QualityApplier", "setParameters rejected for $q")
        return ok
    }

}
