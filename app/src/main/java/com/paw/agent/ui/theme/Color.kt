package com.paw.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Brand seed — indigo -> violet, matching the launcher icon.
 * Used as the fallback palette; on Android 12+ the wallpaper-derived dynamic
 * palette takes over when the user has enabled it.
 */

private val Indigo = Color(0xFF4F46E5)
private val Violet = Color(0xFF7C3AED)
private val Fuchsia = Color(0xFF9333EA)
private val Rose = Color(0xFFE11D48)
private val Sky = Color(0xFF0EA5E9)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Fuchsia,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFAE8FF),
    onTertiaryContainer = Color(0xFF4A044E),
    error = Rose,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
    surfaceContainer = Color(0xFFF1EFF6),
    surfaceContainerHigh = Color(0xFFEBE9F0),
    surfaceContainerHighest = Color(0xFFE5E3EA),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3EFF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2FF),
    onPrimary = Color(0xFF1B2678),
    primaryContainer = Color(0xFF333E90),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFD9C9FF),
    onSecondary = Color(0xFF381E72),
    secondaryContainer = Color(0xFF4F378B),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFFFFB0F5),
    onTertiary = Color(0xFF5C0073),
    tertiaryContainer = Color(0xFF7B2296),
    onTertiaryContainer = Color(0xFFFFD6FF),
    error = Color(0xFFFFB3B5),
    onError = Color(0xFF5F1120),
    errorContainer = Color(0xFF8C1D2C),
    onErrorContainer = Color(0xFFFFD9DC),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF47464F),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A2930),
    surfaceContainerHighest = Color(0xFF35343B),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
)

/** Accent used for the streaming caret, tuned per brightness. */
val StreamingCaretLight = Color(0xFF7C3AED)
val StreamingCaretDark = Color(0xFFD9C9FF)

/** A muted teal used to tint "user" bubbles so they read as distinct. */
val UserBubbleLight = Color(0xFFE0E7FF)
val UserBubbleDark = Color(0xFF333E90)

/**
 * Resolves the colour scheme actually in effect, accounting for the Material You
 * dynamic palette.
 */
@Composable
fun agentPawColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
}

/**
 * The app theme.
 *
 * @param darkTheme defaults to the system setting when null.
 */
@Composable
fun AgentPawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = agentPawColorScheme(darkTheme, dynamicColor),
        typography = AgentPawTypography,
        shapes = AgentPawShapes,
        content = content,
    )
}
