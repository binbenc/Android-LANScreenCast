plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.android.compose)
    alias(libs.plugins.wsc.hilt)
}

// 反向控制（PRD F23）发送端：无障碍服务（只注入点击 / 手势 / 全局动作，不读取屏幕内容）、授权编排、
// 控制确认页 R03、边缘胶囊 R05、设置 R01 与引导 R02。
dependencies {
    api(projects.core.control)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
}
