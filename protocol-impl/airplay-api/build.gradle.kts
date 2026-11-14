plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)
}
