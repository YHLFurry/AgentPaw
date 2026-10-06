package com.paw.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.paw.agent.data.settings.UiThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * The single theme root of the app.
 *
 * It picks the design system from the persisted [UiThemeMode] and publishes a
 * matching [AppThemeSpec], so every screen below it is written once against
 * theme-agnostic tokens. Because the mode arrives as state from DataStore,
 * flipping it re-composes the whole tree — no Activity restart needed.
 *
 * @param uiTheme which design system to render with.
 * @param darkTheme dark palette for either design system.
 * @param dynamicColor Material You wallpaper palette; Material-only.
 */
@Composable
fun AgentPawAppTheme(
    uiTheme: UiThemeMode,
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    when (uiTheme) {
        UiThemeMode.MATERIAL -> AgentPawTheme(
            darkTheme = darkTheme,
            dynamicColor = dynamicColor,
        ) {
            CompositionLocalProvider(LocalAppThemeSpec provides rememberMaterialSpec()) {
                content()
            }
        }

        UiThemeMode.MIUIX -> {
            // ThemeController freezes its mode at construction, so a new one is
            // created whenever the user toggles the dark palette.
            val controller = remember(darkTheme) {
                ThemeController(
                    colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                )
            }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalAppThemeSpec provides rememberMiuixSpec()) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun rememberMaterialSpec(): AppThemeSpec {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val shape = MaterialTheme.shapes
    return remember(scheme, type, shape) {
        AppThemeSpec(
            mode = UiThemeMode.MATERIAL,
            colors = AppColors(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                primaryContainer = scheme.primaryContainer,
                onPrimaryContainer = scheme.onPrimaryContainer,
                secondaryContainer = scheme.secondaryContainer,
                onSecondaryContainer = scheme.onSecondaryContainer,
                tertiaryContainer = scheme.tertiaryContainer,
                onTertiaryContainer = scheme.onTertiaryContainer,
                error = scheme.error,
                onError = scheme.onError,
                errorContainer = scheme.errorContainer,
                onErrorContainer = scheme.onErrorContainer,
                background = scheme.background,
                onBackground = scheme.onBackground,
                surface = scheme.surface,
                onSurface = scheme.onSurface,
                surfaceVariant = scheme.surfaceVariant,
                onSurfaceVariant = scheme.onSurfaceVariant,
                surfaceContainer = scheme.surfaceContainer,
                onSurfaceContainer = scheme.onSurface,
                surfaceContainerHigh = scheme.surfaceContainerHigh,
                onSurfaceContainerHigh = scheme.onSurface,
                surfaceContainerHighest = scheme.surfaceContainerHighest,
                outline = scheme.outline,
            ),
            typography = AppTypography(
                headlineSmall = type.headlineSmall,
                titleMedium = type.titleMedium,
                titleSmall = type.titleSmall,
                bodyLarge = type.bodyLarge,
                bodyMedium = type.bodyMedium,
                bodySmall = type.bodySmall,
                labelLarge = type.labelLarge,
                labelMedium = type.labelMedium,
                labelSmall = type.labelSmall,
            ),
            shapes = AppShapes(
                small = shape.small,
                medium = shape.medium,
                large = shape.large,
            ),
        )
    }
}

@Composable
private fun rememberMiuixSpec(): AppThemeSpec {
    val colors = MiuixTheme.colorScheme
    val type = MiuixTheme.textStyles
    return remember(colors, type) {
        AppThemeSpec(
            mode = UiThemeMode.MIUIX,
            colors = AppColors(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                primaryContainer = colors.primaryContainer,
                onPrimaryContainer = colors.onPrimaryContainer,
                secondaryContainer = colors.secondaryContainer,
                onSecondaryContainer = colors.onSecondaryContainer,
                tertiaryContainer = colors.tertiaryContainer,
                onTertiaryContainer = colors.onTertiaryContainer,
                error = colors.error,
                onError = colors.onError,
                errorContainer = colors.errorContainer,
                onErrorContainer = colors.onErrorContainer,
                background = colors.background,
                onBackground = colors.onBackground,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = colors.surfaceVariant,
                onSurfaceVariant = colors.onSurfaceVariantSummary,
                surfaceContainer = colors.surfaceContainer,
                onSurfaceContainer = colors.onSurfaceContainer,
                surfaceContainerHigh = colors.surfaceContainerHigh,
                onSurfaceContainerHigh = colors.onSurfaceContainerHigh,
                surfaceContainerHighest = colors.surfaceContainerHighest,
                outline = colors.outline,
            ),
            typography = AppTypography(
                headlineSmall = type.title3,
                titleMedium = type.headline2,
                titleSmall = type.subtitle,
                bodyLarge = type.body1,
                bodyMedium = type.body2,
                bodySmall = type.footnote2,
                labelLarge = type.footnote1,
                labelMedium = type.footnote2,
                labelSmall = type.footnote2,
            ),
            shapes = AppShapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
            ),
        )
    }
}
