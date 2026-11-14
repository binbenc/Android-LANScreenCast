import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** 全工程统一的 SDK / Java 版本（技术方案第 5 节：minSdk 29、compileSdk 37、Java 17 字节码）。 */
object WscBuildConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 29
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    const val NAMESPACE_ROOT = "com.benchan.wireless.screencasting"
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Android 应用与库共用的配置。 */
internal fun configureAndroidCommon(extension: CommonExtension) {
    extension.compileSdk = WscBuildConfig.COMPILE_SDK
    extension.compileOptions.sourceCompatibility = WscBuildConfig.JAVA_VERSION
    extension.compileOptions.targetCompatibility = WscBuildConfig.JAVA_VERSION
    extension.lint.apply {
        abortOnError = true
        checkDependencies = false
        // 预编译 libwebrtc / 协议库里的警告不阻断构建；只有我们自己的 error 级问题阻断
        warningsAsErrors = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
    extension.testOptions.unitTests.isReturnDefaultValues = true
}
