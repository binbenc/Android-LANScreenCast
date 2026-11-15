plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.android.compose)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.security)
    implementation(projects.core.signaling)
    implementation(projects.core.discovery)
    implementation(projects.core.net)
    implementation(projects.core.rtc)
    // 反向控制（PRD F23）：授权编排、无障碍注入、确认页
    implementation(projects.feature.control)
    // F14 分享页在自己的任务里打开网络诊断 / 安装帮助（交互稿 S06）
    implementation(projects.feature.settings)
    // DLNA 推送（F13）：推送引擎、文件服务、转换；锁屏媒体控件与音量键调电视音量（Media3 MediaSession）
    implementation(projects.protocol.dlna)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // 扫码：CameraX + ZXing core 自行解码（不使用 ML Kit / Play Services）
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
