plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.protocol)
    api(projects.core.security)
    implementation(projects.core.net)
    // WSS 服务端（接收端）与客户端（发送端），技术方案 3.5.1
    implementation(libs.java.websocket)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.bouncycastle.bcpkix)
}
