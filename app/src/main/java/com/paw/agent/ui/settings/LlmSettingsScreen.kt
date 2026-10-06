package com.paw.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.R
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppCircularProgressIndicator
import com.paw.agent.ui.components.adaptive.AppDivider
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSectionTitle
import com.paw.agent.ui.components.adaptive.AppSlider
import com.paw.agent.ui.components.adaptive.AppSnackbarHost
import com.paw.agent.ui.components.adaptive.AppSnackbarHostState
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppSwitch
import com.paw.agent.ui.components.adaptive.AppTabRow
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextButton
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.rememberAppSnackbarHostState
import com.paw.agent.ui.theme.AppTheme
import kotlin.math.roundToInt

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
 * The settings screen: two independent sections behind a tab row.
 *
 * The LLM section and the appearance section own separate ViewModels and never
 * write to each other's state, so editing a model cannot disturb the theme and
 * switching the theme cannot disturb an in-progress LLM draft.
 */
@Composable
fun SettingsScreen(
    llmState: LlmSettingsUiState,
    llmActions: LlmSettingsActions,
    appearanceState: AppearanceUiState,
    appearanceActions: AppearanceActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = rememberAppSnackbarHostState()

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.settings_title),
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AppTabRow(
                tabs = listOf(
                    stringResource(R.string.settings_tab_llm),
                    stringResource(R.string.settings_tab_appearance),
                ),
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxWidth(),
            )

            when (selectedTab) {
                0 -> LlmSettingsPane(
                    state = llmState,
                    actions = llmActions,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.weight(1f),
                )

                else -> AppearancePane(
                    state = appearanceState,
                    actions = appearanceActions,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The LLM section. Unchanged in behaviour: it still edits a draft copy of
 * [LlmSettingsUiState], validates on save, and only then writes to DataStore.
 */
@Composable
fun LlmSettingsPane(
    state: LlmSettingsUiState,
    actions: LlmSettingsActions,
    snackbarHostState: AppSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val savedMessage = stringResource(R.string.settings_saved)

    LaunchedEffect(state.savedAt) {
        if (state.savedAt != null) snackbarHostState.showSnackbar(savedMessage)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ---- provider ----
        SettingsSection(title = stringResource(R.string.settings_section_provider)) {
            AppText(
                text = stringResource(R.string.settings_provider),
                style = AppTheme.typography.labelLarge,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ProviderChips(
                selected = state.provider,
                onSelect = actions.onProviderChange,
            )
        }

        // ---- connection ----
        SettingsSection(title = stringResource(R.string.settings_section_connection)) {
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

            Spacer(Modifier.height(12.dp))

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
                    AppIconButton(onClick = actions.onToggleApiKeyVisibility) {
                        AppIcon(
                            imageVector = if (state.showApiKey) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = stringResource(
                                if (state.showApiKey) {
                                    R.string.settings_hide_api_key
                                } else {
                                    R.string.settings_show_api_key
                                },
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- model ----
        SettingsSection(title = stringResource(R.string.settings_section_model)) {
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

            Spacer(Modifier.height(8.dp))

            SwitchRow(
                title = stringResource(R.string.settings_stream),
                subtitle = stringResource(R.string.settings_stream_summary),
                checked = state.stream,
                onCheckedChange = actions.onStreamChange,
            )
        }

        // ---- sampling ----
        SettingsSection(title = stringResource(R.string.settings_section_sampling)) {
            SliderRow(
                label = stringResource(R.string.settings_temperature),
                value = state.temperature,
                valueRange = 0f..2f,
                steps = 19,
                display = formatTwoDecimals(state.temperature),
                onValueChange = actions.onTemperatureChange,
            )

            SliderRow(
                label = stringResource(R.string.settings_top_p),
                value = state.topP,
                valueRange = 0f..1f,
                steps = 19,
                display = formatTwoDecimals(state.topP),
                onValueChange = actions.onTopPChange,
            )

            Spacer(Modifier.height(8.dp))

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

        // ---- system prompt ----
        SettingsSection(title = stringResource(R.string.settings_section_system_prompt)) {
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

        // ---- agent execution & vision ----
        SettingsSection(title = stringResource(R.string.settings_section_agent_vision)) {
            StepSettingsControl(
                maxToolRounds = state.maxToolRounds,
                onMaxToolRoundsChange = actions.onMaxToolRoundsChange,
            )

            Spacer(Modifier.height(12.dp))

            AppText(
                text = stringResource(R.string.settings_vision_resolution),
                style = AppTheme.typography.labelLarge,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            AppText(
                text = stringResource(R.string.settings_vision_resolution_summary),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.outline,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val modes = listOf(
                    "AUTO" to R.string.settings_vision_auto,
                    "FAST" to R.string.settings_vision_fast,
                    "HIGH" to R.string.settings_vision_high,
                )
                modes.forEach { (modeKey, modeTitle) ->
                    val isSelected = state.visionResolutionMode == modeKey
                    AppCard(
                        onClick = { actions.onVisionResolutionModeChange(modeKey) },
                        modifier = Modifier.weight(1f),
                        cornerRadius = 12.dp,
                        containerColor = if (isSelected) {
                            AppTheme.colors.secondaryContainer
                        } else {
                            AppTheme.colors.surfaceContainerHigh
                        },
                        contentColor = if (isSelected) {
                            AppTheme.colors.onSecondaryContainer
                        } else {
                            AppTheme.colors.onSurfaceVariant
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    ) {
                        AppText(
                            text = stringResource(modeTitle),
                            style = AppTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }

        // ---- device & system permissions ----
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

        SettingsSection(title = stringResource(R.string.settings_section_permissions)) {
            PermissionItem(
                title = stringResource(R.string.permission_accessibility),
                subtitle = stringResource(R.string.permission_accessibility_summary),
                isGranted = isAccessibilityEnabled,
                statusText = stringResource(R.string.permission_granted),
                actionText = stringResource(R.string.permission_open_settings),
                onAction = { com.paw.agent.device.DevicePermissionManager.openAccessibilitySettings(context) },
            )

            Spacer(Modifier.height(10.dp))

            PermissionItem(
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
                    val dispatched = com.paw.agent.device.DevicePermissionManager.requestShizukuPermission()
                    if (!dispatched) {
                        com.paw.agent.device.DevicePermissionManager.openShizukuApp(context)
                    }
                },
            )

            Spacer(Modifier.height(10.dp))

            PermissionItem(
                title = stringResource(R.string.permission_overlay),
                subtitle = stringResource(R.string.permission_overlay_summary),
                isGranted = hasOverlayPermission,
                statusText = stringResource(R.string.permission_granted),
                actionText = stringResource(R.string.permission_open_settings),
                onAction = { com.paw.agent.device.DevicePermissionManager.openOverlaySettings(context) },
            )
        }

        // ---- actions ----
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppButton(
                onClick = actions.onSave,
                modifier = Modifier.weight(1f),
            ) {
                AppText(stringResource(R.string.settings_save))
            }

            AppOutlinedButton(
                onClick = actions.onTestConnection,
                enabled = state.testState !is TestState.Running,
                modifier = Modifier.weight(1f),
            ) {
                if (state.testState is TestState.Running) {
                    AppCircularProgressIndicator()
                    Spacer(Modifier.width(8.dp))
                    AppText(stringResource(R.string.settings_testing))
                } else {
                    AppText(stringResource(R.string.settings_test_connection))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
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

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        AppSectionTitle(text = title)
        Spacer(Modifier.height(12.dp))
        Column(content = content)
        Spacer(Modifier.height(8.dp))
        AppDivider()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProviderChips(
    selected: LlmProvider,
    onSelect: (LlmProvider) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LlmProvider.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { provider ->
                    ProviderChip(
                        provider = provider,
                        isSelected = provider == selected,
                        onClick = { onSelect(provider) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // keep the last row aligned when it has a single item
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProviderChip(
    provider: LlmProvider,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(
        onClick = onClick,
        modifier = modifier,
        cornerRadius = 12.dp,
        containerColor = if (isSelected) {
            AppTheme.colors.secondaryContainer
        } else {
            AppTheme.colors.surfaceContainerHigh
        },
        contentColor = if (isSelected) {
            AppTheme.colors.onSecondaryContainer
        } else {
            AppTheme.colors.onSurfaceVariant
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    ) {
        AppText(text = provider.displayName, style = AppTheme.typography.labelLarge)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
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
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            AppText(
                text = subtitle,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
        AppSwitch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
        )
    }
}

@Composable
private fun TestResultBanner(testState: TestState) {
    val (message, isError) = when (testState) {
        is TestState.Success -> testState.message to false
        is TestState.Failure -> testState.message to true
        TestState.Idle, TestState.Running -> return
    }

    AppCard(
        containerColor = if (isError) {
            AppTheme.colors.errorContainer
        } else {
            AppTheme.colors.tertiaryContainer
        },
        contentColor = if (isError) {
            AppTheme.colors.onErrorContainer
        } else {
            AppTheme.colors.onTertiaryContainer
        },
        cornerRadius = 12.dp,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppText(text = message, style = AppTheme.typography.bodySmall)
    }
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

@Composable
private fun PermissionItem(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    statusText: String,
    actionText: String?,
    onAction: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        containerColor = AppTheme.colors.surfaceContainer,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = title,
                        style = AppTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppSurface(
                        shape = AppTheme.shapes.small,
                        color = if (isGranted) {
                            AppTheme.colors.primaryContainer
                        } else {
                            AppTheme.colors.surfaceVariant
                        },
                        contentColor = if (isGranted) {
                            AppTheme.colors.onPrimaryContainer
                        } else {
                            AppTheme.colors.onSurfaceVariant
                        },
                    ) {
                        AppText(
                            text = statusText,
                            style = AppTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = subtitle,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            if (!isGranted && actionText != null) {
                Spacer(Modifier.width(8.dp))
                AppOutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    AppText(text = actionText, style = AppTheme.typography.labelSmall)
                }
            }
        }
    }
}

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
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurface,
            )
            AppSurface(
                shape = AppTheme.shapes.small,
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

        Spacer(Modifier.height(10.dp))

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

        Spacer(Modifier.height(8.dp))

        // 快速预设按钮
        AppText(
            text = stringResource(R.string.settings_max_rounds_presets),
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val presets = listOf(
                0 to R.string.settings_max_rounds_preset_unlimited,
                10 to R.string.settings_max_rounds_preset_10,
                20 to R.string.settings_max_rounds_preset_20,
                30 to R.string.settings_max_rounds_preset_30,
                50 to R.string.settings_max_rounds_preset_50,
            )
            presets.forEach { (stepVal, labelRes) ->
                val isSelected = (stepVal == 0 && maxToolRounds <= 0) ||
                    (stepVal > 0 && maxToolRounds == stepVal)
                AppCard(
                    onClick = {
                        inputText = stepVal.toString()
                        onMaxToolRoundsChange(stepVal)
                    },
                    modifier = Modifier.weight(1f),
                    cornerRadius = 8.dp,
                    containerColor = if (isSelected) {
                        AppTheme.colors.primaryContainer
                    } else {
                        AppTheme.colors.surfaceContainerHigh
                    },
                    contentColor = if (isSelected) {
                        AppTheme.colors.onPrimaryContainer
                    } else {
                        AppTheme.colors.onSurfaceVariant
                    },
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(
                            text = stringResource(labelRes),
                            style = AppTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
