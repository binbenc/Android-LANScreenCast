plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.common)
    // UPnP 纯逻辑（SOAP、DIDL、描述解析、推送引擎），DMR 与 DMC 共用（技术方案 3.13.1）
    api(projects.protocol.upnp)
    implementation(projects.core.net)
    implementation(projects.core.data)
    // DMR 播放（技术方案 3.7）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    // DMC 视频“转换后推送”（PRD 8.1.1 F2）：系统硬件编解码，不引入 FFmpeg
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
}
