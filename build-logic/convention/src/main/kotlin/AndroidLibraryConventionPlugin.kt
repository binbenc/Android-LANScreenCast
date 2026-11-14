import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig.minSdk = WscBuildConfig.MIN_SDK
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            // 模块 namespace 由路径推导，例如 :core:rtc -> ...screencasting.core.rtc
            if (namespace == null) {
                namespace = WscBuildConfig.NAMESPACE_ROOT + path.replace(':', '.').replace('-', '.')
            }
        }
        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
