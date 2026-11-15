package com.benchan.wireless.screencasting.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor as TvLocalContentColor
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Typography as TvTypographyType

/** 把同一份 Token 同步给 Compose for TV（androidx.tv.material3）的主题，TV 组件与 M3 组件颜色一致；并提供 onSurface 作为默认内容色。 */
@Composable
internal fun TvThemeBridge(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val t = MaterialTheme.typography
    TvMaterialTheme(
        colorScheme = tvDarkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer,
            onPrimaryContainer = c.onPrimaryContainer, inversePrimary = c.inversePrimary,
            secondary = c.secondary, onSecondary = c.onSecondary, secondaryContainer = c.secondaryContainer,
            onSecondaryContainer = c.onSecondaryContainer, tertiary = c.tertiary, onTertiary = c.onTertiary,
            tertiaryContainer = c.tertiaryContainer, onTertiaryContainer = c.onTertiaryContainer,
            background = c.background, onBackground = c.onBackground, surface = c.surface, onSurface = c.onSurface,
            surfaceVariant = c.surfaceContainerHighest, onSurfaceVariant = c.onSurfaceVariant, surfaceTint = c.primary,
            inverseSurface = c.inverseSurface, inverseOnSurface = c.inverseOnSurface,
            error = c.error, onError = c.onError, errorContainer = c.errorContainer, onErrorContainer = c.onErrorContainer,
            border = c.outline, borderVariant = c.outlineVariant, scrim = c.scrim,
        ),
        typography = TvTypographyType(
            displayLarge = t.displayLarge, displayMedium = t.displayMedium, displaySmall = t.displaySmall,
            headlineLarge = t.headlineLarge, headlineMedium = t.headlineMedium, headlineSmall = t.headlineSmall,
            titleLarge = t.titleLarge, titleMedium = t.titleMedium, titleSmall = t.titleSmall,
            bodyLarge = t.bodyLarge, bodyMedium = t.bodyMedium, bodySmall = t.bodySmall,
            labelLarge = t.labelLarge, labelMedium = t.labelMedium, labelSmall = t.labelSmall,
        ),
    ) {
        // tv-material 的 LocalContentColor 默认也是 Color.Black，未包在 tv Surface 里的 tv Text / Icon 会变黑
        CompositionLocalProvider(TvLocalContentColor provides c.onSurface, content = content)
    }
}
