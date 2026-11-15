package com.benchan.wireless.screencasting.core.rtc.receiver

/**
 * 解码器厂商私有低延迟键（F18，技术方案 3.4；PRD 8.1.3 E “厂商私有键按兼容表维护”）。
 *
 * 按 MediaCodec 名称前缀匹配（OMX 与 Codec2 两种命名）。键名来自各芯片厂商公开的 MediaCodec 扩展参数，
 * 不认识的键 MediaCodec 一般直接忽略；个别 ROM 抛异常时由 WscLowLatencyMediaCodecFactory 回退到原始参数。
 * TODO(M0-T4 / 兼容表): 每个厂商在真机上用“首包进解码器 → onOutputBufferAvailable”耗时验证确有收益后再保留。
 */
object DecoderLowLatencyKeys {
    private val rules: List<Pair<List<String>, Map<String, Int>>> = listOf(
        // 高通
        listOf("omx.qcom.", "c2.qti.") to mapOf(
            "vendor.qti-ext-dec-low-latency.enable" to 1,
            "vendor.qti-ext-dec-picture-order.enable" to 1,
        ),
        // 华为海思
        listOf("omx.hisi.", "c2.hisi.") to mapOf(
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
        ),
        // 三星 Exynos
        listOf("omx.exynos.", "c2.exynos.") to mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1),
        // Amlogic（大量电视盒子）
        listOf("omx.amlogic.", "c2.amlogic.") to mapOf("vendor.low-latency.enable" to 1),
    )

    /** @return 该解码器要额外设置的整型键；不认识的厂商返回空表。 */
    @JvmStatic
    fun vendorKeys(codecName: String?): Map<String, Int> {
        val n = codecName?.lowercase() ?: return emptyMap()
        return rules.firstOrNull { (prefixes, _) -> prefixes.any(n::startsWith) }?.second ?: emptyMap()
    }
}
