plugins {
    alias(libs.plugins.wsc.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.serialization.json)
    // SPAKE2（RFC 9382）的 P-256 点运算
    implementation(libs.bouncycastle.bcprov)
}

// 与 TS 端共享的测试向量位于仓库根目录 protocol/vectors
tasks.withType<Test>().configureEach {
    systemProperty("wsc.vectors.dir", rootProject.layout.projectDirectory.dir("protocol/vectors").asFile.absolutePath)
}
