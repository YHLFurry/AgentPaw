package com.paw.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.ui.components.adaptive.AppDivider
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSnackbarHost
import com.paw.agent.ui.components.adaptive.AppSnackbarHostState
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppSlider
import com.paw.agent.ui.components.adaptive.AppSwitch
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextButton
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.theme.AppTheme

/*
 * Shared building blocks for the settings screens.
 *
 * The settings pages are built from a small, fixed set of primitives so every
 * row has the same height and padding no matter what it contains. That is what
 * keeps the grid of providers, the switches and the permission rows visually
 * aligned instead of each sizing itself from its own text.
 */

/** Every card uses the same corner radius so groups read as one family. */
private val CardCorner = 16.dp

/** Row height floor: keeps a one-line switch row and a two-line row consistent. */
private val RowMinHeight = 56.dp

/** Fixed provider-cell height — the single reason no grid row can be taller. */
private val ProviderCellHeight = 54.dp

/**
 * The quiet label above a group of settings.
 *
 * Deliberately *not* tinted with `primary`: the previous screen used a coloured
 * heading for every section and another heading right below it, which is what
 * made the hierarchy collapse. Group labels stay muted; the content carries the
 * emphasis.
 */
@Composable
fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    AppText(
        text = text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        style = AppTheme.typography.labelLarge,
        color = AppTheme.colors.onSurfaceVariant,
    )
}

/** Rounded container that groups related settings. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        color = AppTheme.colors.surfaceContainer,
        contentColor = AppTheme.colors.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** One row inside a [SettingsCard]; uniform padding keeps rows the same height. */
@Composable
fun SettingsRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Divider between rows, inset so it does not touch the card edge. */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    AppDivider(modifier = modifier.padding(horizontal = 16.dp))
}

/** Full-width padded block for a text field or a slider inside a card. */
@Composable
fun SettingsBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/**
 * A navigation row: icon, title, summary, optional trailing value and a chevron.
 * Used on the settings home to open a section.
 */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    SettingsRow(modifier = modifier.clickable(onClick = onClick)) {
        AppSurface(
            shape = RoundedCornerShape(12.dp),
            color = AppTheme.colors.secondaryContainer,
            contentColor = AppTheme.colors.onSecondaryContainer,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                AppIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            AppText(
                text = title,
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            AppText(
                text = summary,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            AppText(
                text = trailing,
                modifier = Modifier.widthIn(max = 108.dp),
                style = AppTheme.typography.labelMedium,
                color = AppTheme.colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(6.dp))
        AppIcon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = AppTheme.colors.onSurfaceVariant,
        )
    }
}

/**
 * The provider picker: a two-column grid of equal-width, equal-height cells.
 *
 * Fixed height is the whole point — the old picker let a long name wrap to two
 * lines, so one row in the grid was taller than its neighbour. Labels are
 * single-line with an ellipsis instead, and the full name is printed under the
 * grid so abbreviating never hides information.
 */
@Composable
fun ProviderGrid(
    selected: LlmProvider,
    onSelect: (LlmProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LlmProvider.entries.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { provider ->
                    ProviderCell(
                        provider = provider,
                        isSelected = provider == selected,
                        onClick = { onSelect(provider) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last, odd cell at half width instead of letting it stretch.
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProviderCell(
    provider: LlmProvider,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(ProviderCellHeight)
            .clip(shape)
            .background(
                if (isSelected) {
                    AppTheme.colors.primaryContainer
                } else {
                    AppTheme.colors.surfaceContainerHigh
                },
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) {
                    AppTheme.colors.primary
                } else {
                    AppTheme.colors.outline.copy(alpha = 0.30f)
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AppText(
            text = provider.gridLabel,
            style = AppTheme.typography.labelLarge,
            color = if (isSelected) {
                AppTheme.colors.onPrimaryContainer
            } else {
                AppTheme.colors.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A one-line label derived from [LlmProvider.displayName] for the grid.
 * `Google Gemini (OpenAI compat)` becomes `Google Gemini`; the full name is
 * still shown directly beneath the grid.
 */
internal val LlmProvider.gridLabel: String
    get() {
        val short = displayName.substringBefore(" (")
        return if (short.length >= 4) short else displayName
    }

/** Title + summary + switch, on one padded row. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(modifier = modifier) {
        Column(Modifier.weight(1f)) {
            AppText(
                text = title,
                style = AppTheme.typography.bodyLarge,
                color = if (enabled) {
                    AppTheme.colors.onSurface
                } else {
                    AppTheme.colors.onSurfaceVariant
                },
            )
            if (!subtitle.isNullOrBlank()) {
                AppText(
                    text = subtitle,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        AppSwitch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
        )
    }
}

/** Label, live value and slider on one padded block. */
@Composable
fun SettingsSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = label,
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurface,
            )
            AppText(
                text = display,
                style = AppTheme.typography.labelLarge,
                color = AppTheme.colors.primary,
            )
        }
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/** Small tinted chip used for permission state. */
@Composable
fun StatusPill(
    text: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
) {
    AppSurface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (granted) {
            AppTheme.colors.primaryContainer
        } else {
            AppTheme.colors.surfaceVariant
        },
        contentColor = if (granted) {
            AppTheme.colors.onPrimaryContainer
        } else {
            AppTheme.colors.onSurfaceVariant
        },
    ) {
        AppText(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = AppTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * A permission row. The action button is always full width, so its size no
 * longer depends on how long its label happens to be.
 */
@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    statusText: String,
    actionText: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = title,
                modifier = Modifier.weight(1f),
                style = AppTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            StatusPill(text = statusText, granted = isGranted)
        }

        Spacer(Modifier.height(6.dp))
        AppText(
            text = subtitle,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )

        if (!isGranted && actionText != null) {
            Spacer(Modifier.height(10.dp))
            AppOutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
            ) {
                AppText(text = actionText, style = AppTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The shell every settings page shares: back arrow, title, optional trailing
 * actions and a scrolling body. Trailing actions live here so a primary action
 * like "Save" stays on screen instead of sitting at the bottom of a long list.
 */
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: AppSnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopAppBar(
                title = title,
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = {
            if (snackbarHostState != null) AppSnackbarHost(snackbarHostState)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/** The "Save" action, sized and placed identically on every page that has one. */
@Composable
fun SettingsSaveAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTextButton(
        text = stringResource(R.string.settings_save),
        onClick = onClick,
        modifier = modifier.padding(end = 4.dp),
    )
}
