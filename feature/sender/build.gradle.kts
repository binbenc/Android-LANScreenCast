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
