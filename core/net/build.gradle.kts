plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

dependencies {
    api(projects.core.common)
    // 局域网明文 HTTP（DLNA、F26 安装包分发）；CIO 不支持服务端 TLS，所以不承载 WSC 信令
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.android)
}
