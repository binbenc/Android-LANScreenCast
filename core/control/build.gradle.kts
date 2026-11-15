plugins {
    alias(libs.plugins.wsc.jvm.library)
}

// 反向控制（PRD F23）的纯算法：坐标换算、手势分段、指针合并、注入门控。不依赖 Android，JVM 单测覆盖。
dependencies {
    api(projects.core.protocol)
}
