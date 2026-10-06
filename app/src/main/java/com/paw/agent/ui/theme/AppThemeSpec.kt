package com.paw.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.paw.agent.data.settings.UiThemeMode

/**
 * Theme-agnostic design tokens.
 *
 * Every screen reads colours, text styles and shapes from here instead of from
 * `MaterialTheme` / `MiuixTheme` directly, so the same composable can be
 * rendered by either design system. Each theme root (see `AppTheme.kt`) builds
 * one of these from its own palette.
 */
@Immutable
data class AppColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val onSurfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onSurfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
)

/** The slice of the type scale the app actually uses. */
@Immutable
data class AppTypography(
    val headlineSmall: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
)

@Immutable
data class AppShapes(
    val small: Shape,
    val medium: Shape,
    val large: Shape,
)

@Immutable
data class AppThemeSpec(
    /** Which design system is currently rendering; adaptive components branch on it. */
    val mode: UiThemeMode,
    val colors: AppColors,
    val typography: AppTypography,
    val shapes: AppShapes,
)

/**
 * Fallback used when a composable is rendered outside a theme root (previews,
 * tests). Kept deliberately plain — it is never the on-device value.
 */
internal val DefaultAppThemeSpec = AppThemeSpec(
    mode = UiThemeMode.MATERIAL,
    colors = AppColors(
        primary = Color(0xFF4F46E5),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E7FF),
        onPrimaryContainer = Color(0xFF1E1B4B),
        secondaryContainer = Color(0xFFEDE9FE),
        onSecondaryContainer = Color(0xFF2E1065),
        tertiaryContainer = Color(0xFFFAE8FF),
        onTertiaryContainer = Color(0xFF4A044E),
        error = Color(0xFFE11D48),
        onError = Color.White,
        errorContainer = Color(0xFFFFE4E6),
        onErrorContainer = Color(0xFF881337),
        background = Color(0xFFFCFCFF),
        onBackground = Color(0xFF1B1B21),
        surface = Color(0xFFFCFCFF),
        onSurface = Color(0xFF1B1B21),
        surfaceVariant = Color(0xFFE4E1EC),
        onSurfaceVariant = Color(0xFF47464F),
        surfaceContainer = Color(0xFFF1EFF6),
        onSurfaceContainer = Color(0xFF1B1B21),
        surfaceContainerHigh = Color(0xFFEBE9F0),
        onSurfaceContainerHigh = Color(0xFF1B1B21),
        surfaceContainerHighest = Color(0xFFE5E3EA),
        outline = Color(0xFF787680),
    ),
    typography = AppTypography(
        headlineSmall = TextStyle.Default,
        titleMedium = TextStyle.Default,
        titleSmall = TextStyle.Default,
        bodyLarge = TextStyle.Default,
        bodyMedium = TextStyle.Default,
        bodySmall = TextStyle.Default,
        labelLarge = TextStyle.Default,
        labelMedium = TextStyle.Default,
        labelSmall = TextStyle.Default,
    ),
    shapes = AppShapes(
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
    ),
)

val LocalAppThemeSpec = compositionLocalOf { DefaultAppThemeSpec }

/** Entry point for screens: `AppTheme.colors.primary`, `AppTheme.typography.bodyLarge`. */
object AppTheme {

    val spec: AppThemeSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalAppThemeSpec.current

    val mode: UiThemeMode
        @Composable
        @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.mode

    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.colors

    val typography: AppTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.typography

    val shapes: AppShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.shapes

    val isMiuix: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.mode == UiThemeMode.MIUIX
}
