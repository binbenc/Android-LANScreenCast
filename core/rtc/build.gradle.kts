plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.protocol)
    // libwebrtc：M0 预编译包，对外以 api 暴露 SurfaceViewRenderer 等视图类
    api(libs.webrtc.android)
    implementation(libs.kotlinx.coroutines.android)
}
