plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(projects.core.protocol)
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
