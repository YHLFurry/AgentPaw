package com.paw.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.R
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.components.adaptive.AppCircularProgressIndicator
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppRadioPreference
import com.paw.agent.ui.components.adaptive.AppSnackbarHostState
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextButton
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.theme.AppTheme
import kotlin.math.roundToInt

/*
 * The settings pages.
 *
 * Structure: one home page listing four sections, then one page per section.
 * The previous single page stacked every setting behind two tabs and put the
 * only Save button at the very bottom, so the primary action was off-screen on
 * first open. Now each editing page carries Save in the app bar.
 *
 * Every setting that existed before still exists, with the same meaning and the
 * same backing field in [LlmSettingsUiState] / [AppearanceUiState].
 */

/** Everything the LLM section can do, grouped so the host screen stays readable. */
data class LlmSettingsActions(
    val onProviderChange: (LlmProvider) -> Unit,
    val onBaseUrlChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onModelChange: (String) -> Unit,
    val onTemperatureChange: (Float) -> Unit,
    val onTopPChange: (Float) -> Unit,
    val onMaxTokensChange: (Int) -> Unit,
    val onMaxToolRoundsChange: (Int) -> Unit,
    val onVisionResolutionModeChange: (String) -> Unit,
    val onStreamChange: (Boolean) -> Unit,
    val onSystemPromptChange: (String) -> Unit,
    val onToggleApiKeyVisibility: () -> Unit,
    val onSave: () -> Unit,
    val onTestConnection: () -> Unit,
    val onReset: () -> Unit,
)

/**
 * The settings home: four grouped entry points instead of one 16-item scroll.
 *
 * @param providerName current provider, shown as the trailing value.
 * @param themeName current design system, shown as the trailing value.
 */
@Composable
fun SettingsHomeScreen(
    providerName: String,
    themeName: String,
    onOpenModelService: () -> Unit,
    onOpenGeneration: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAppearance: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsCard {
            SettingsNavRow(
                icon = Icons.Outlined.Cloud,
                title = stringResource(R.string.settings_group_model_service),
                summary = stringResource(R.string.settings_group_model_service_summary),
                trailing = providerName,
                onClick = onOpenModelService,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsNavRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.settings_group_generation),
                summary = stringResource(R.string.settings_group_generation_summary),
                onClick = onOpenGeneration,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsNavRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = stringResource(R.string.settings_group_agent),
                summary = stringResource(R.string.settings_group_agent_summary),
                onClick = onOpenAgent,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsNavRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_group_appearance),
                summary = stringResource(R.string.settings_group_appearance_summary),
                trailing = themeName,
                onClick = onOpenAppearance,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Model service: provider, connection and the model name.
 *
 * Editing still works on a draft — Save is in the app bar, and testing the
 * connection stays next to the fields it tests.
 */
@Composable
fun LlmServicePage(
    state: LlmSettingsUiState,
    actions: LlmSettingsActions,
    onBack: () -> Unit,
    snackbarHostState: AppSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val savedMessage = stringResource(R.string.settings_saved)
    SavedEffect(state.savedAt, savedMessage, snackbarHostState)

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_model_service),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { SettingsSaveAction(onClick = actions.onSave) },
    ) {
        // ---- provider ----
        SettingsGroupLabel(stringResource(R.string.settings_section_provider))
        SettingsCard {
            SettingsBlock {
                ProviderGrid(
                    selected = state.provider,
                    onSelect = actions.onProviderChange,
                )
                Spacer(Modifier.height(10.dp))
                AppText(
                    text = stringResource(
                        R.string.settings_current_provider,
                        state.provider.displayName,
                    ),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- connection ----
        SettingsGroupLabel(stringResource(R.string.settings_section_connection))
        SettingsCard {
            SettingsBlock {
                AppTextField(
                    value = state.baseUrl,
                    onValueChange = actions.onBaseUrlChange,
                    label = stringResource(R.string.settings_base_url),
                    singleLine = true,
                    isError = state.baseUrlError != null,
                    supportingText = state.baseUrlError?.let { errorText(it) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsRowDivider()

            SettingsBlock {
                AppTextField(
                    value = state.apiKey,
                    onValueChange = actions.onApiKeyChange,
                    label = stringResource(R.string.settings_api_key),
                    singleLine = true,
                    isError = state.apiKeyError != null,
                    supportingText = state.apiKeyError?.let { errorText(it) }
                        ?: stringResource(R.string.settings_api_key_stored),
                    visualTransformation = if (state.showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        AppIconToggle(
                            visible = state.showApiKey,
                            onToggle = actions.onToggleApiKeyVisibility,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- model ----
        SettingsGroupLabel(stringResource(R.string.settings_section_model))
        SettingsCard {
            SettingsBlock {
                AppTextField(
                    value = state.model,
                    onValueChange = actions.onModelChange,
                    label = stringResource(R.string.settings_model),
                    singleLine = true,
                    isError = state.modelError != null,
                    supportingText = state.modelError?.let { errorText(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsRowDivider()

            SettingsSwitchRow(
                title = stringResource(R.string.settings_stream),
                subtitle = stringResource(R.string.settings_stream_summary),
                checked = state.stream,
                onCheckedChange = actions.onStreamChange,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- connection test ----
        AppOutlinedButton(
            onClick = actions.onTestConnection,
            enabled = state.testState !is TestState.Running,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (state.testState is TestState.Running) {
                AppCircularProgressIndicator()
                Spacer(Modifier.width(8.dp))
                AppText(stringResource(R.string.settings_testing))
            } else {
                AppText(
                    text = stringResource(R.string.settings_test_connection),
                    style = AppTheme.typography.labelLarge,
                )
            }
        }

        TestResultBanner(state.testState)

        AppTextButton(
            text = stringResource(R.string.settings_reset),
            onClick = actions.onReset,
            color = AppTheme.colors.error,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(32.dp))
    }
}

/** Sampling parameters and the system prompt. */
@Composable
fun GenerationPage(
    state: LlmSettingsUiState,
    actions: LlmSettingsActions,
    onBack: () -> Unit,
    snackbarHostState: AppSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val savedMessage = stringResource(R.string.settings_saved)
    SavedEffect(state.savedAt, savedMessage, snackbarHostState)

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_generation),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { SettingsSaveAction(onClick = actions.onSave) },
    ) {
        SettingsGroupLabel(stringResource(R.string.settings_section_sampling))
        SettingsCard {
            SettingsSliderRow(
                label = stringResource(R.string.settings_temperature),
                value = state.temperature,
                valueRange = 0f..2f,
                steps = 19,
                display = formatTwoDecimals(state.temperature),
                onValueChange = actions.onTemperatureChange,
            )

            SettingsRowDivider()

            SettingsSliderRow(
                label = stringResource(R.string.settings_top_p),
                value = state.topP,
                valueRange = 0f..1f,
                steps = 19,
                display = formatTwoDecimals(state.topP),
                onValueChange = actions.onTopPChange,
            )

            SettingsRowDivider()

            SettingsBlock {
                AppTextField(
                    value = state.maxTokens.toString(),
                    onValueChange = {
                        actions.onMaxTokensChange(it.filter(Char::isDigit).toIntOrNull() ?: 0)
                    },
                    label = stringResource(R.string.settings_max_tokens),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SettingsGroupLabel(stringResource(R.string.settings_section_system_prompt))
        SettingsCard {
            SettingsBlock {
                AppTextField(
                    value = state.systemPrompt,
                    onValueChange = actions.onSystemPromptChange,
                    label = stringResource(R.string.settings_system_prompt),
                    supportingText = stringResource(R.string.settings_system_prompt_hint),
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** Phone control: execution rounds, screenshot strategy and system permissions. */
@Composable
fun AgentControlPage(
    state: LlmSettingsUiState,
    actions: LlmSettingsActions,
    onBack: () -> Unit,
    snackbarHostState: AppSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val savedMessage = stringResource(R.string.settings_saved)
    SavedEffect(state.savedAt, savedMessage, snackbarHostState)

    val context = androidx.compose.ui.platform.LocalContext.current
    val permRefreshTick = remember { mutableIntStateOf(0) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        permRefreshTick.intValue++
    }

    val isAccessibilityEnabled = remember(permRefreshTick.intValue) {
        com.paw.agent.device.DevicePermissionManager.isAccessibilityServiceEnabled(context)
    }
    // Shizuku 状态改为响应式订阅：binder 到达、授权对话框返回后实时刷新
    val shizukuStatus by com.paw.agent.device.DevicePermissionManager.observeShizukuState()
        .collectAsStateWithLifecycle()
    val isShizukuRunning = shizukuStatus != com.paw.agent.device.shizuku.ShizukuStatus.NOT_RUNNING
    val hasShizukuPermission = shizukuStatus == com.paw.agent.device.shizuku.ShizukuStatus.GRANTED
    val hasOverlayPermission = remember(permRefreshTick.intValue) {
        com.paw.agent.device.DevicePermissionManager.canDrawOverlays(context)
    }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_agent),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { SettingsSaveAction(onClick = actions.onSave) },
    ) {
        SettingsGroupLabel(stringResource(R.string.settings_group_execution))
        SettingsCard {
            SettingsBlock {
                StepSettingsControl(
                    maxToolRounds = state.maxToolRounds,
                    onMaxToolRoundsChange = actions.onMaxToolRoundsChange,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SettingsGroupLabel(stringResource(R.string.settings_vision_resolution))
        SettingsCard {
            SettingsBlock {
                AppText(
                    text = stringResource(R.string.settings_vision_resolution_summary),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                VisionModeSelector(
                    current = state.visionResolutionMode,
                    onSelect = actions.onVisionResolutionModeChange,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SettingsGroupLabel(stringResource(R.string.settings_group_system_permissions))
        SettingsCard {
            PermissionRow(
                title = stringResource(R.string.permission_accessibility),
                subtitle = stringResource(R.string.permission_accessibility_summary),
                isGranted = isAccessibilityEnabled,
                statusText = if (isAccessibilityEnabled) {
                    stringResource(R.string.permission_granted)
                } else {
                    stringResource(R.string.permission_not_granted)
                },
                actionText = stringResource(R.string.permission_open_settings),
                onAction = {
                    com.paw.agent.device.DevicePermissionManager
                        .openAccessibilitySettings(context)
                },
            )

            SettingsRowDivider()

            PermissionRow(
                title = stringResource(R.string.permission_shizuku),
                subtitle = when (shizukuStatus) {
                    com.paw.agent.device.shizuku.ShizukuStatus.GRANTED ->
                        stringResource(R.string.permission_shizuku_granted)

                    com.paw.agent.device.shizuku.ShizukuStatus.RUNNING_NO_PERMISSION ->
                        stringResource(R.string.permission_shizuku_waiting)

                    com.paw.agent.device.shizuku.ShizukuStatus.NOT_RUNNING ->
                        stringResource(R.string.permission_shizuku_not_running)
                },
                isGranted = hasShizukuPermission,
                statusText = when {
                    hasShizukuPermission -> stringResource(R.string.permission_granted)
                    isShizukuRunning -> stringResource(R.string.permission_shizuku_pending)
                    else -> stringResource(R.string.permission_not_granted)
                },
                // 只要未授权就提供按钮：运行中直接拉起授权对话框；未运行则引导打开 Shizuku 应用
                actionText = if (!hasShizukuPermission) {
                    stringResource(R.string.permission_shizuku_request)
                } else {
                    null
                },
                onAction = {
                    val dispatched = com.paw.agent.device.DevicePermissionManager
                        .requestShizukuPermission()
                    if (!dispatched) {
                        com.paw.agent.device.DevicePermissionManager.openShizukuApp(context)
                    }
                },
            )

            SettingsRowDivider()

            PermissionRow(
                title = stringResource(R.string.permission_overlay),
                subtitle = stringResource(R.string.permission_overlay_summary),
                isGranted = hasOverlayPermission,
                statusText = if (hasOverlayPermission) {
                    stringResource(R.string.permission_granted)
                } else {
                    stringResource(R.string.permission_not_granted)
                },
                actionText = stringResource(R.string.permission_open_settings),
                onAction = {
                    com.paw.agent.device.DevicePermissionManager.openOverlaySettings(context)
                },
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Appearance: design system, palette and Material You.
 *
 * No Save button here on purpose — a theme change must take effect on the same
 * frame, so it is written straight through instead of staged in a draft.
 */
@Composable
fun AppearanceSettingsPage(
    state: AppearanceUiState,
    actions: AppearanceActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_appearance),
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsGroupLabel(stringResource(R.string.settings_section_theme))
        SettingsCard {
            UiThemeMode.entries.forEachIndexed { index, mode ->
                AppRadioPreference(
                    title = stringResource(mode.themeTitleRes),
                    summary = stringResource(mode.summaryRes),
                    selected = state.uiTheme == mode,
                    onClick = { actions.onUiThemeChange(mode) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (index != UiThemeMode.entries.lastIndex) SettingsRowDivider()
            }

            SettingsBlock {
                AppText(
                    text = stringResource(R.string.settings_theme_switch_hint),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.outline,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SettingsGroupLabel(stringResource(R.string.settings_section_palette))
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_dark_theme),
                subtitle = stringResource(R.string.settings_dark_theme_summary),
                checked = state.darkTheme,
                onCheckedChange = actions.onDarkThemeChange,
            )

            SettingsRowDivider()

            // Material You 取色只影响 Material 主题；Miuix 使用自己的调色板。
            SettingsSwitchRow(
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

        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------------------
// Local pieces
// ---------------------------------------------------------------------------

/**
 * Shows the "saved" snackbar once per save.
 *
 * [savedAt] lives in a ViewModel shared by every settings page, so a plain
 * `LaunchedEffect(savedAt)` would fire again the moment another page was
 * opened — announcing a save that happened somewhere else. Seeding the
 * remembered value with the timestamp already present on entry makes the
 * effect fire only on an actual change while this page is on screen.
 */
@Composable
private fun SavedEffect(
    savedAt: Long?,
    message: String,
    hostState: AppSnackbarHostState,
) {
    var lastShown by remember { mutableStateOf(savedAt) }
    LaunchedEffect(savedAt) {
        val at = savedAt ?: return@LaunchedEffect
        if (at == lastShown) return@LaunchedEffect
        lastShown = at
        hostState.showSnackbar(message)
    }
}

@Composable
private fun AppIconToggle(
    visible: Boolean,
    onToggle: () -> Unit,
) {
    AppIconButton(onClick = onToggle) {
        AppIcon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.settings_hide_api_key else R.string.settings_show_api_key,
            ),
        )
    }
}

/** Equal-width, equal-height cells for the screenshot strategy. */
@Composable
private fun VisionModeSelector(
    current: String,
    onSelect: (String) -> Unit,
) {
    val modes = listOf(
        "AUTO" to R.string.settings_vision_auto,
        "FAST" to R.string.settings_vision_fast,
        "HIGH" to R.string.settings_vision_high,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modes.forEach { (modeKey, modeTitle) ->
            SelectableCell(
                label = stringResource(modeTitle),
                isSelected = current == modeKey,
                onClick = { onSelect(modeKey) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A fixed-size selectable block. Used wherever the screen needs a row of
 * options whose widths must not depend on the label length.
 */
@Composable
private fun SelectableCell(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 48,
    corner: Int = 10,
) {
    val shape = RoundedCornerShape(corner.dp)
    Box(
        modifier = modifier
            .height(height.dp)
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
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = label,
            style = AppTheme.typography.labelMedium,
            color = if (isSelected) {
                AppTheme.colors.onPrimaryContainer
            } else {
                AppTheme.colors.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TestResultBanner(testState: TestState) {
    when (testState) {
        is TestState.Success -> {
            Spacer(Modifier.height(10.dp))
            ResultBanner(
                text = stringResource(R.string.settings_test_ok),
                isError = false,
            )
        }

        is TestState.Failure -> {
            Spacer(Modifier.height(10.dp))
            ResultBanner(
                text = stringResource(R.string.settings_test_failed) + "\n" + testState.message,
                isError = true,
            )
        }

        TestState.Idle, TestState.Running -> Unit
    }
}

@Composable
private fun ResultBanner(text: String, isError: Boolean) {
    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) {
            AppTheme.colors.errorContainer
        } else {
            AppTheme.colors.tertiaryContainer
        },
        contentColor = if (isError) {
            AppTheme.colors.onErrorContainer
        } else {
            AppTheme.colors.onTertiaryContainer
        },
    ) {
        AppText(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = AppTheme.typography.bodySmall,
        )
    }
}

/**
 * Max execution rounds. The internal logic (5 rounds to a cell, 0 = unlimited,
 * presets) is unchanged; only the presentation is rebuilt on fixed-size cells.
 */
@Composable
private fun StepSettingsControl(
    maxToolRounds: Int,
    onMaxToolRoundsChange: (Int) -> Unit,
) {
    // 计数规则：每 5 步记为 1 格
    val currentGrids = if (maxToolRounds <= 0) {
        stringResource(R.string.settings_max_rounds_unlimited_short)
    } else {
        if (maxToolRounds % 5 == 0) {
            stringResource(R.string.settings_max_rounds_grids, maxToolRounds / 5)
        } else {
            String.format(java.util.Locale.US, "%.1f 格", maxToolRounds / 5.0)
        }
    }

    var inputText by remember(maxToolRounds) {
        mutableStateOf(if (maxToolRounds <= 0) "0" else maxToolRounds.toString())
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = stringResource(R.string.settings_max_rounds),
                modifier = Modifier.weight(1f),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            AppSurface(
                shape = RoundedCornerShape(8.dp),
                color = if (maxToolRounds <= 0) {
                    AppTheme.colors.tertiaryContainer
                } else {
                    AppTheme.colors.primaryContainer
                },
                contentColor = if (maxToolRounds <= 0) {
                    AppTheme.colors.onTertiaryContainer
                } else {
                    AppTheme.colors.onPrimaryContainer
                },
            ) {
                AppText(
                    text = if (maxToolRounds <= 0) {
                        stringResource(R.string.settings_max_rounds_unlimited)
                    } else {
                        stringResource(R.string.settings_max_rounds_value, maxToolRounds, currentGrids)
                    },
                    style = AppTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        AppText(
            text = stringResource(R.string.settings_max_rounds_hint),
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.outline,
        )

        Spacer(Modifier.height(12.dp))

        // 自定义步数输入框
        AppTextField(
            value = inputText,
            onValueChange = { newStr ->
                val filtered = newStr.filter { it.isDigit() }
                inputText = filtered
                val parsed = filtered.toIntOrNull()
                if (parsed != null && parsed >= 0) {
                    onMaxToolRoundsChange(parsed)
                }
            },
            label = stringResource(R.string.settings_max_rounds_custom),
            placeholder = stringResource(R.string.settings_max_rounds_custom_hint),
            supportingText = run {
                val num = inputText.toIntOrNull() ?: 0
                if (num <= 0) {
                    stringResource(R.string.settings_max_rounds_convert_unlimited)
                } else {
                    val g = if (num % 5 == 0) {
                        stringResource(R.string.settings_max_rounds_grids, num / 5)
                    } else {
                        String.format(java.util.Locale.US, "%.1f 格", num / 5.0)
                    }
                    stringResource(R.string.settings_max_rounds_convert, num, g)
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // 快速预设按钮
        AppText(
            text = stringResource(R.string.settings_max_rounds_presets),
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // Three per row: five cells across a phone width squeezed the longer
        // labels ("50步(10格)") into an ellipsis.
        val presets = listOf(
            0 to R.string.settings_max_rounds_preset_unlimited,
            10 to R.string.settings_max_rounds_preset_10,
            20 to R.string.settings_max_rounds_preset_20,
            30 to R.string.settings_max_rounds_preset_30,
            50 to R.string.settings_max_rounds_preset_50,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowItems.forEach { (stepVal, labelRes) ->
                        val isSelected = (stepVal == 0 && maxToolRounds <= 0) ||
                            (stepVal > 0 && maxToolRounds == stepVal)
                        SelectableCell(
                            label = stringResource(labelRes),
                            isSelected = isSelected,
                            onClick = {
                                inputText = stepVal.toString()
                                onMaxToolRoundsChange(stepVal)
                            },
                            modifier = Modifier.weight(1f),
                            height = 38,
                            corner = 8,
                        )
                    }
                    // Keep the trailing row aligned when it is not full.
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Display name of a design system, reused by the settings home trailing value. */
internal val UiThemeMode.themeTitleRes: Int
    get() = when (this) {
        UiThemeMode.MATERIAL -> R.string.settings_theme_material
        UiThemeMode.MIUIX -> R.string.settings_theme_miuix
    }

private val UiThemeMode.summaryRes: Int
    get() = when (this) {
        UiThemeMode.MATERIAL -> R.string.settings_theme_material_summary
        UiThemeMode.MIUIX -> R.string.settings_theme_miuix_summary
    }

/** Maps a validation key stored in the ViewModel onto a localised string. */
@Composable
private fun errorText(key: String): String = when (key) {
    "error_base_url_required" -> stringResource(R.string.error_base_url_required)
    "error_base_url_invalid" -> stringResource(R.string.error_base_url_invalid)
    "error_model_required" -> stringResource(R.string.error_model_required)
    "error_api_key_required" -> stringResource(R.string.error_api_key_required)
    "error_temperature_range" -> stringResource(R.string.error_temperature_range)
    "error_max_tokens_range" -> stringResource(R.string.error_max_tokens_range)
    "error_top_p_range" -> stringResource(R.string.error_top_p_range)
    else -> key
}

private fun formatTwoDecimals(value: Float): String =
    ((value * 100).roundToInt() / 100f).toString()
