plugins {
    alias(libs.plugins.wsc.jvm.library)
}

// 纯 Kotlin 的 UPnP / DLNA 逻辑（技术方案 3.13.1）：SOAP、DIDL-Lite、protocolInfo、设备描述、LastChange、
// HTTP Range、推送状态机与控制点 HTTP 客户端。DMR（接收）与 DMC（推送）共用，单测跑在 JVM 上。
dependencies {
    api(projects.core.common)
    // 控制点 HTTP 客户端：项目已有依赖（Apache-2.0），只访问局域网 IP 字面量
    api(libs.okhttp)
}
