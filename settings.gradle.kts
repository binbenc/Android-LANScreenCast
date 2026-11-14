pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "WirelessScreenCasting"

include(":app")

include(":core:common")
include(":core:protocol")
include(":core:signaling")
include(":core:security")
include(":core:discovery")
include(":core:net")
include(":core:rtc")
include(":core:data")
include(":core:ui")

include(":feature:sender")
include(":feature:receiver")
include(":feature:settings")

// 仓库根目录的 protocol/ 是与 TS 端共享的协议文档与测试向量（非 Gradle），
// Gradle 路径 :protocol:* 映射到 protocol-impl/ 目录（技术方案第 5 节）。
include(":protocol:dlna")
include(":protocol:airplay-api")
include(":protocol:airplay")
project(":protocol").projectDir = file("protocol-impl")
project(":protocol:dlna").projectDir = file("protocol-impl/dlna")
project(":protocol:airplay-api").projectDir = file("protocol-impl/airplay-api")
project(":protocol:airplay").projectDir = file("protocol-impl/airplay")
