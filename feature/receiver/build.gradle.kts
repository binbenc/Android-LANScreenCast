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
    // 反向控制（PRD F23）：坐标换算与输入合并
    implementation(projects.core.control)
    implementation(projects.protocol.dlna)
    implementation(projects.protocol.airplayApi)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
}
