package com.benchan.wireless.screencasting.core.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.ui.components.LocalIsTv
import com.benchan.wireless.screencasting.core.ui.components.TvFocusIndication

/** M3 只内置 error；成功、警告、延迟色阶以 Extended Colors 补齐（交互稿 03）。 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val latencyExcellent: Color,
    val latencyGood: Color,
    val latencyFair: Color,
    val latencyPoor: Color,
) {
    fun latency(grade: LatencyGrade?): Color = when (grade) {
        LatencyGrade.EXCELLENT -> latencyExcellent
        LatencyGrade.GOOD -> latencyGood
        LatencyGrade.FAIR -> latencyFair
        LatencyGrade.POOR -> latencyPoor
        null -> latencyGood
    }
}

val LightColors: ColorScheme = lightColorScheme(
    primary = primaryLight, onPrimary = onPrimaryLight, primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight, inversePrimary = inversePrimaryLight,
    secondary = secondaryLight, onSecondary = onSecondaryLight, secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight, tertiary = tertiaryLight, onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight, onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight, onError = onErrorLight, errorContainer = errorContainerLight, onErrorContainer = onErrorContainerLight,
    background = surfaceLight, onBackground = onSurfaceLight,
    surface = surfaceLight, onSurface = onSurfaceLight, surfaceVariant = surfaceVariantLight, onSurfaceVariant = onSurfaceVariantLight,
    surfaceDim = surfaceDimLight, surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight, surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight, surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
    outline = outlineLight, outlineVariant = outlineVariantLight,
    inverseSurface = inverseSurfaceLight, inverseOnSurface = inverseOnSurfaceLight, scrim = scrimLight,
    surfaceTint = primaryLight,
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = primaryDark, onPrimary = onPrimaryDark, primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark, inversePrimary = inversePrimaryDark,
    secondary = secondaryDark, onSecondary = onSecondaryDark, secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark, tertiary = tertiaryDark, onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark, onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark, onError = onErrorDark, errorContainer = errorContainerDark, onErrorContainer = onErrorContainerDark,
    background = surfaceDark, onBackground = onSurfaceDark,
    surface = surfaceDark, onSurface = onSurfaceDark, surfaceVariant = surfaceVariantDark, onSurfaceVariant = onSurfaceVariantDark,
    surfaceDim = surfaceDimDark, surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark, surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark, surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
    outline = outlineDark, outlineVariant = outlineVariantDark,
    inverseSurface = inverseSurfaceDark, inverseOnSurface = inverseOnSurfaceDark, scrim = scrimLight,
    surfaceTint = primaryDark,
)

val LightExtended = ExtendedColors(
    successLight, onSuccessLight, successContainerLight, onSuccessContainerLight,
    warningLight, onWarningLight, warningContainerLight, onWarningContainerLight,
    latency1Light, latency2Light, latency3Light, latency4Light,
)

val DarkExtended = ExtendedColors(
    successDark, onSuccessDark, successContainerDark, onSuccessContainerDark,
    warningDark, onWarningDark, warningContainerDark, onWarningContainerDark,
    latency1Dark, latency2Dark, latency3Dark, latency4Dark,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** 圆角随组件尺寸递增：XS 4 / S 8 / M 12 / L 16 / XL 28（交互稿 06）。 */
val WscShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 叠在 scrim（叠加透明度 ≥ [ScrimAlpha.Text]）或视频 / 相机画面上的文字与图标色（交互稿 token on-scrim），
 * 避免在页面里硬编码 Color.White（D1）。文字本身不要再叠加透明度。
 */
val ColorScheme.onScrim: Color
    get() = if (surface.luminance() < 0.5f) onScrimDark else onScrimLight

/** 遮罩透明度两档（交互稿 SCRIM_ALPHA）。 */
object ScrimAlpha {
    /** 只压暗背景，上面没有文字。 */
    const val Dim = 0.32f

    /** 遮罩上有文字：保证文字对比度。 */
    const val Text = 0.6f
}

/** 当前主题的扩展语义色。 */
object WscTheme {
    val extended: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

/**
 * 应用主题。手机 / 平板默认跟随系统浅深色；TV 接收端固定深色（交互稿 11：大屏暗环境下亮背景造成眩光与烧屏风险）。
 */
@Composable
fun WscAppTheme(
    tv: Boolean = false,
    darkTheme: Boolean = tv || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val ext = if (darkTheme) DarkExtended else LightExtended
    CompositionLocalProvider(LocalExtendedColors provides ext) {
        MaterialTheme(
            colorScheme = colors,
            typography = if (tv) TvTypography else PhoneTypography,
            shapes = WscShapes,
        ) {
            // M3 的 LocalContentColor 默认是 Color.Black：根部不是 Surface/Scaffold 时，未指定 color 的 Text、
            // 未指定 tint 的 Icon 会在深色底上画成黑色（约 1.1:1）。主题层统一兜底为 onSurface。
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                if (tv) {
                    // TvThemeBridge 内同样为 androidx.tv.material3.LocalContentColor 兜底
                    TvThemeBridge {
                        // TV：M3 clickable 行改用明显的焦点样式（默认水波纹的焦点叠色对比度只有约 1.2:1）
                        CompositionLocalProvider(
                            LocalIsTv provides true,
                            LocalIndication provides TvFocusIndication(colors.primary, colors.onSurface),
                            content = content,
                        )
                    }
                } else {
                    content()
                }
            }
        }
    }
}
