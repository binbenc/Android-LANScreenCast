plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.protocol)
    implementation(projects.core.net)
    implementation(projects.core.security)
    implementation(projects.core.data)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
}
