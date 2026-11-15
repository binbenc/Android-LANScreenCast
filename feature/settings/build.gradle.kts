plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.android.compose)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.security)
    implementation(projects.core.discovery)
    implementation(projects.core.net)
    implementation(projects.protocol.airplayApi)
    // 09A“反向控制”行显示无障碍服务状态（PM 走查 G9）
    implementation(projects.feature.control)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
}
