plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.net)
    implementation(projects.core.data)
    // DMR 播放（技术方案 3.7）
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.kotlinx.coroutines.android)
}
