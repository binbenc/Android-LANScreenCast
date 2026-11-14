plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(projects.core.protocol)
    implementation(projects.core.data)
    // 自签 X.509 证书生成（技术方案 3.5.2）
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)
}
