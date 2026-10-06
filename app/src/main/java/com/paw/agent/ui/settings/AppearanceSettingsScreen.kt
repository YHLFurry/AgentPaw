package com.paw.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.components.adaptive.AppRadioPreference
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.theme.AppTheme

/**
 * The appearance section: theme system, dark mode and Material You.
 *
 * Changes are applied the moment they are written — the theme mode lives in
 * DataStore, and [com.paw.agent.ui.theme.AgentPawAppTheme] re-composes the whole
 * tree when it changes, so tapping an option re-themes every screen (including
 * this one) without restarting the app.
 */
@Composable
fun AppearancePane(
    state: AppearanceUiState,
    actions: AppearanceActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_theme)) {
            UiThemeMode.entries.forEach { mode ->
                AppRadioPreference(
                    title = stringResource(mode.titleRes),
                    summary = stringResource(mode.summaryRes),
                    selected = state.uiTheme == mode,
                    onClick = { actions.onUiThemeChange(mode) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(4.dp))
            AppText(
                text = stringResource(R.string.settings_theme_switch_hint),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.outline,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_palette)) {
            SwitchRow(
                title = stringResource(R.string.settings_dark_theme),
                subtitle = stringResource(R.string.settings_dark_theme_summary),
                checked = state.darkTheme,
                onCheckedChange = actions.onDarkThemeChange,
            )

            Spacer(Modifier.height(8.dp))

            // Material You 取色只影响 Material 主题；Miuix 使用自己的调色板。
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(
                    if (state.uiTheme == UiThemeMode.MATERIAL) {
                        R.string.settings_dynamic_color_summary
                    } else {
                        R.string.settings_dynamic_color_material_only
                    },
                ),
                checked = state.dynamicColor,
                enabled = state.uiTheme == UiThemeMode.MATERIAL,
                onCheckedChange = actions.onDynamicColorChange,
            )
        }
    }
}

private val UiThemeMode.titleRes: Int
    get() = when (this) {
        UiThemeMode.MATERIAL -> R.string.settings_theme_material
        UiThemeMode.MIUIX -> R.string.settings_theme_miuix
    }

private val UiThemeMode.summaryRes: Int
    get() = when (this) {
        UiThemeMode.MATERIAL -> R.string.settings_theme_material_summary
        UiThemeMode.MIUIX -> R.string.settings_theme_miuix_summary
    }
