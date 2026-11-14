plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.android.compose)
}

dependencies {
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.tv.material)
    api(projects.core.protocol)
    implementation(libs.zxing.core)
}
