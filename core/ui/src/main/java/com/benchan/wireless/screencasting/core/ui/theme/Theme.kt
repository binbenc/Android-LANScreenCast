package com.benchan.wireless.screencasting.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade

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
            if (tv) TvThemeBridge(content) else content()
        }
    }
}
