@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.paw.agent.ui.components.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost as MiuixSnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.basic.TabRow as MiuixTabRow
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/*
 * Adaptive UI primitives.
 *
 * Each `App*` composable renders the Material 3 component under the Material
 * theme and the Miuix component under the Miuix theme. The Material branch keeps
 * the exact calls the app used before Miuix existed, so existing users see no
 * visual change; the Miuix branch is a pure presentation swap driven by the same
 * state and callbacks.
 */

@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = AppTheme.colors.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (AppTheme.isMiuix) {
        MiuixScaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            containerColor = containerColor,
            content = content,
        )
    } else {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            containerColor = containerColor,
            content = content,
        )
    }
}

/**
 * @param titleContent an optional rich title (icon + text). Miuix renders a fixed
 *   [title]/[subtitle] pair, so it ignores this and the caller passes the same
 *   information as strings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    color: Color = AppTheme.colors.surfaceContainer,
) {
    if (AppTheme.isMiuix) {
        SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            titleColor = AppTheme.colors.onSurface,
            subtitle = subtitle,
            subtitleColor = AppTheme.colors.onSurfaceVariant,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        TopAppBar(
            title = {
                if (titleContent != null) {
                    titleContent()
                } else {
                    Column {
                        AppText(
                            text = title,
                            style = AppTheme.typography.titleMedium,
                            color = AppTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle.isNotBlank()) {
                            AppText(
                                text = subtitle,
                                style = AppTheme.typography.labelSmall,
                                color = AppTheme.colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = color),
        )
    }
}

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.onSurface,
    style: TextStyle = AppTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    fontWeight: FontWeight? = null,
) {
    if (AppTheme.isMiuix) {
        MiuixText(
            text = text,
            modifier = modifier,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = maxLines,
            minLines = minLines,
            overflow = overflow,
            style = style,
        )
    } else {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = maxLines,
            minLines = minLines,
            overflow = overflow,
            style = style,
        )
    }
}

/** Section heading used above a group of settings. */
@Composable
fun AppSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (AppTheme.isMiuix) {
        SmallTitle(text = text, modifier = modifier, textColor = AppTheme.colors.primary)
    } else {
        AppText(
            text = text,
            modifier = modifier,
            style = AppTheme.typography.titleSmall,
            color = AppTheme.colors.primary,
        )
    }
}

@Composable
fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = AppTheme.colors.onSurface,
) {
    if (AppTheme.isMiuix) {
        MiuixIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    } else {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}

@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (AppTheme.isMiuix) {
        MiuixIconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    } else {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    }
}

@Composable
fun AppCircularProgressIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    strokeWidth: Dp = 2.dp,
) {
    if (AppTheme.isMiuix) {
        MiuixCircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth, size = size)
    } else {
        CircularProgressIndicator(modifier = modifier.size(size), strokeWidth = strokeWidth)
    }
}

@Composable
fun AppLinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier,
    )
}

@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    if (AppTheme.isMiuix) {
        MiuixHorizontalDivider(modifier = modifier)
    } else {
        HorizontalDivider(modifier = modifier)
    }
}

@Composable
fun AppSurface(
    modifier: Modifier = Modifier,
    shape: Shape = AppTheme.shapes.medium,
    color: Color = AppTheme.colors.surface,
    contentColor: Color = AppTheme.colors.onSurface,
    /**
     * Material-only: a tonal elevation overlay. Miuix has no equivalent and
     * ignores it, so callers must pass a meaningful [color] too.
     */
    tonalElevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (AppTheme.isMiuix) {
        if (onClick != null) {
            MiuixSurface(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                color = color,
                contentColor = contentColor,
            ) { content() }
        } else {
            MiuixSurface(
                modifier = modifier,
                shape = shape,
                color = color,
                contentColor = contentColor,
            ) { content() }
        }
    } else {
        if (onClick != null) {
            Surface(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                color = color,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
            ) { content() }
        } else {
            Surface(
                modifier = modifier,
                shape = shape,
                color = color,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
            ) { content() }
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = AppTheme.colors.surfaceContainer,
    contentColor: Color = AppTheme.colors.onSurface,
    cornerRadius: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (AppTheme.isMiuix) {
        if (onClick != null) {
            MiuixCard(
                modifier = modifier.padding(contentPadding),
                cornerRadius = cornerRadius,
                insideMargin = PaddingValues(0.dp),
                colors = MiuixCardDefaults.defaultColors(
                    color = containerColor,
                    contentColor = contentColor,
                ),
                onClick = onClick,
                content = content,
            )
        } else {
            MiuixCard(
                modifier = modifier.padding(contentPadding),
                cornerRadius = cornerRadius,
                insideMargin = PaddingValues(0.dp),
                colors = MiuixCardDefaults.defaultColors(
                    color = containerColor,
                    contentColor = contentColor,
                ),
                content = content,
            )
        }
    } else {
        if (onClick != null) {
            Card(
                onClick = onClick,
                modifier = modifier.padding(contentPadding),
                shape = RoundedCornerShape(cornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
                content = content,
            )
        } else {
            Card(
                modifier = modifier.padding(contentPadding),
                shape = RoundedCornerShape(cornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
                content = content,
            )
        }
    }
}

/** Segmented control used to switch between the settings sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (AppTheme.isMiuix) {
        MiuixTabRow(
            tabs = tabs,
            selectedTabIndex = selectedIndex,
            onTabSelected = onTabSelected,
            modifier = modifier,
        )
    } else {
        PrimaryTabRow(
            selectedTabIndex = selectedIndex,
            modifier = modifier,
            containerColor = AppTheme.colors.surface,
            contentColor = AppTheme.colors.onSurface,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onTabSelected(index) },
                    text = {
                        AppText(
                            text = title,
                            style = AppTheme.typography.labelLarge,
                            color = if (index == selectedIndex) {
                                AppTheme.colors.primary
                            } else {
                                AppTheme.colors.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/** A settings row that selects one option out of a group (theme picker). */
@Composable
fun AppRadioPreference(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    if (AppTheme.isMiuix) {
        RadioButtonPreference(
            title = title,
            selected = selected,
            onClick = if (enabled) onClick else null,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
        )
    } else {
        Row(
            modifier = modifier
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = if (enabled) onClick else null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                AppText(
                    text = title,
                    style = AppTheme.typography.bodyLarge,
                    color = if (enabled) {
                        AppTheme.colors.onSurface
                    } else {
                        AppTheme.colors.onSurfaceVariant
                    },
                )
                if (summary != null) {
                    AppText(
                        text = summary,
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A snackbar host whose state type follows the active theme, so callers push a
 * message without knowing which design system is on screen.
 */
class AppSnackbarHostState {
    internal val materialState = SnackbarHostState()
    internal val miuixState = MiuixSnackbarHostState()
    internal var mode by mutableStateOf(UiThemeMode.MATERIAL)

    suspend fun showSnackbar(message: String) {
        when (mode) {
            UiThemeMode.MATERIAL -> materialState.showSnackbar(message)
            UiThemeMode.MIUIX -> miuixState.showSnackbar(message)
        }
    }
}

@Composable
fun rememberAppSnackbarHostState(): AppSnackbarHostState = remember { AppSnackbarHostState() }

@Composable
fun AppSnackbarHost(state: AppSnackbarHostState, modifier: Modifier = Modifier) {
    val mode = AppTheme.mode
    SideEffect { state.mode = mode }
    if (mode == UiThemeMode.MIUIX) {
        MiuixSnackbarHost(state = state.miuixState, modifier = modifier)
    } else {
        SnackbarHost(hostState = state.materialState, modifier = modifier)
    }
}

/** Shared shape for the round send/stop button in the chat composer. */
val AppCircleShape: Shape = CircleShape

@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    if (AppTheme.mode == UiThemeMode.MIUIX) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
            AppSurface(
                shape = RoundedCornerShape(20.dp),
                color = AppTheme.colors.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides AppTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.onSurface,
                        )
                    ) {
                        title()
                    }
                    Spacer(Modifier.size(12.dp))
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides AppTheme.typography.bodyMedium.copy(
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    ) {
                        text()
                    }
                    Spacer(Modifier.size(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton()
                        Spacer(Modifier.size(8.dp))
                        confirmButton()
                    }
                }
            }
        }
    } else {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            containerColor = AppTheme.colors.surface,
            titleContentColor = AppTheme.colors.onSurface,
            textContentColor = AppTheme.colors.onSurfaceVariant,
        )
    }
}
