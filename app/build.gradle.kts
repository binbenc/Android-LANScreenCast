import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.wsc.android.application)
    alias(libs.plugins.wsc.android.compose)
    alias(libs.plugins.wsc.hilt)
}

// AirPlay 兼容构建开关（技术方案 3.8）：通过 providers 读取，配置缓存友好。默认 false（不包含）。
val airplayEnabled: Boolean = providers.gradleProperty("wsc.airplay").map { it.toBoolean() }.getOrElse(false)

val baseVersionCode = 1

android {
    namespace = "com.benchan.wireless.screencasting"

    defaultConfig {
        applicationId = "com.benchan.wireless.screencasting"
        versionCode = baseVersionCode
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "AIRPLAY_AVAILABLE", airplayEnabled.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    // 按 ABI 拆包（技术方案 3.10.3）。TV 盒子大量是 32 位用户空间，armeabi-v7a 不能省。
    // x86_64 仅供模拟器调试使用。另外输出 universal 包：F26 局域网分发时，手机上装的是什么包就分发什么包，
    // 只有 universal 包才能保证 32 位电视也能安装（见 docs/dev/开发进度.md 已知问题）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // R8 覆盖整个应用（packageScope 默认 "**"）：本项目代码、Ktor、BouncyCastle、OkHttp、material-icons-extended 等
            // 一并裁剪 / 优化。依赖反射或 JNI 的部分由 src/main/keepRules/*.keep 保留（libwebrtc 整包 keep，见 webrtc.keep）。
            optimization {
                enable = true
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                // BouncyCastle 中未使用的资源：后量子 Picnic 参数表（~1.2 MB）与 PKIX 路径审查的多语言消息
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/*.properties",
                "org/bouncycastle/pkix/*.properties",
            )
        }
    }
}

// versionCode = base * 10 + ABI 位（v7a=1、arm64=2、x86_64=3，universal=0），F-Droid 每个 ABI 一个 build 条目
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val digit = when (abi) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                "x86_64" -> 3
                else -> 0
            }
            output.versionCode.set(baseVersionCode * 10 + digit)
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.discovery)
    implementation(projects.feature.sender)
    implementation(projects.feature.receiver)
    implementation(projects.feature.settings)
    implementation(projects.protocol.airplayApi)
    if (airplayEnabled) {
        implementation(projects.protocol.airplay)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
