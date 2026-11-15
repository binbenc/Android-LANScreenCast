plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.protocol)
    // DLNA 设备描述解析与零外联校验（F13，技术方案 3.13.2）
    api(projects.protocol.upnp)
    implementation(projects.core.net)
    implementation(projects.core.security)
    implementation(projects.core.data)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
}
