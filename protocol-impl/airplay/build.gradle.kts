// 兼容 Apple 设备（非官方）接收端：UxPlay via JNI（技术方案 3.8）。
// 只有 wsc.airplay=true（gradle.properties 默认 true）时才编译原生代码，并且 :app 才会依赖本模块。
// Kotlin 部分（含单测）在 wsc.airplay=false 时照常编译 / 测试，只是不会被打进 APK。
plugins {
    alias(libs.plugins.wsc.android.library)
    alias(libs.plugins.wsc.hilt)
}

val airplayEnabled = providers.gradleProperty("wsc.airplay").map { it.toBoolean() }.getOrElse(false)
val airplayAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
// OpenSSL / FFmpeg 预编译产物目录（不入库，见 .gitignore）；可用 -Pwsc.airplay.depsDir=... 指向共享缓存
val nativeDepsDir: File = providers.gradleProperty("wsc.airplay.depsDir").map { file(it) }
    .getOrElse(layout.projectDirectory.dir("native-deps").asFile)

android {
    defaultConfig {
        if (airplayEnabled) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        // 原生代码全部是 C（不用 Oboe），不需要 libc++，避免每个 ABI 多带 1.3~1.8 MB 的 libc++_shared.so
                        "-DANDROID_STL=none",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DWSC_NATIVE_DEPS_DIR=${nativeDepsDir.absolutePath}",
                    )
                }
            }
            ndk {
                abiFilters += airplayAbis
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

if (airplayEnabled) {
    // 交叉编译 OpenSSL 3.x（libcrypto）与 FFmpeg（仅 ALAC 解码器）。脚本自身按子模块 commit + 脚本哈希 + NDK 版本
    // 写 stamp，未变化时秒级跳过，因此任务总是执行、由脚本判断增量（避免让 Gradle 哈希整个 FFmpeg / OpenSSL 源码树）。
    val ndkDirectory = androidComponents.sdkComponents.ndkDirectory
    val depsDirPath = nativeDepsDir.absolutePath
    val abiArg = airplayAbis.joinToString(" ")
    val buildNativeDeps = tasks.register<Exec>("buildNativeDeps") {
        group = "build"
        description = "Cross-compiles OpenSSL (libcrypto) and FFmpeg (ALAC only) for the Apple-compatible receiver."
        inputs.file("scripts/build-native-deps.sh")
        outputs.dir(depsDirPath)
        outputs.upToDateWhen { false }
        workingDir = layout.projectDirectory.asFile
        executable = "bash"
        args("scripts/build-native-deps.sh", "--abis", abiArg, "--api", "29", "--out", depsDirPath)
        argumentProviders.add(CommandLineArgumentProvider { listOf("--ndk", ndkDirectory.get().asFile.absolutePath) })
    }
    tasks.configureEach {
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(buildNativeDeps)
    }
}

dependencies {
    api(projects.protocol.airplayApi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    androidTestImplementation(libs.androidx.junit)
}
