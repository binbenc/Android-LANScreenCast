plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)
    // 视频网址播放器接口（media3-common 已是 exoplayer 的传递依赖，不是新依赖）
    api(libs.androidx.media3.common)
}
