plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(projects.core.protocol)
    // TV 反向控制模式（TvControlMode）的持久化
    api(projects.core.control)
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
