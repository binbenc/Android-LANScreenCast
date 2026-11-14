// 兼容 Apple 设备（非官方）接收端：UxPlay via JNI（技术方案 3.8）。
// 只有 -Pwsc.airplay=true 时才编译原生代码，并且 :app 才会依赖本模块；默认构建不包含。
plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

val airplayEnabled = providers.gradleProperty("wsc.airplay").map { it.toBoolean() }.getOrElse(false)

android {
    defaultConfig {
        if (airplayEnabled) {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                }
            }
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }
    if (airplayEnabled) {
        ndkVersion = "27.0.12077973"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    api(projects.protocol.airplayApi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
}
