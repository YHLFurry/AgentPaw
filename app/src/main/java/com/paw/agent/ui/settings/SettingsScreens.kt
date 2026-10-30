package com.paw.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppAlertDialog
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
    val onMaxTokensInputChange: (String) -> Unit = {},
    val onMaxTotalTokensInputChange: (String) -> Unit = {},
    val onMaxTotalTokensChange: (Int) -> Unit = {},
    val onReasoningEffortChange: (String) -> Unit = {},
    val onAutoCompressChange: (Boolean) -> Unit = {},
    val onMaxToolRoundsChange: (Int) -> Unit,
    val onMaxToolRoundsInputChange: (String) -> Unit = {},
    val onVisionResolutionModeChange: (String) -> Unit,
    val onTimeoutSecondsChange: (Int) -> Unit = {},
    val onTimeoutSecondsInputChange: (String) -> Unit = {},
    val onPacingProfileChange: (String) -> Unit = {},
    val onStreamChange: (Boolean) -> Unit,
    val onSystemPromptChange: (String) -> Unit,
    val onToggleApiKeyVisibility: () -> Unit,
    val onSave: () -> Unit,
    val onTestConnection: () -> Unit,
    val onRunDiagnostics: () -> Unit = {},
    val onReset: () -> Unit,
    val onToggleExpertMode: (Boolean) -> Unit = {},
    val onToggleSplitVisionLanguage: (Boolean) -> Unit = {},
    val onToggleRootMode: (Boolean) -> Unit = {},
    val onToggleAdaptivePacing: (Boolean) -> Unit = {},
    // ---- 资深模式：高危开关 ----
    val onToggleHighRisk: (com.paw.agent.data.settings.HighRiskToggle, Boolean) -> Unit = { _, _ -> },
    val onStartRiskBypass: () -> Unit = {},
    val onStopRiskBypass: () -> Unit = {},
    val onConsumeRiskNotice: () -> Unit = {},
    // ---- 联网拉取模型 ----
    val onFetchModels: () -> Unit = {},
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
    expertMode: Boolean,
    onOpenModelService: () -> Unit,
    onOpenGeneration: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenExpert: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    hasUnsavedChanges: Boolean = false,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (hasUnsavedChanges) AppText(stringResource(R.string.settings_unsaved),
            style = AppTheme.typography.bodySmall, color = AppTheme.colors.primary,
            modifier = Modifier.padding(bottom = 12.dp))
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

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsNavRow(
                icon = Icons.Outlined.Build,
                title = stringResource(R.string.skills_title),
                summary = stringResource(R.string.skills_summary),
                onClick = onOpenSkills,
            )
        }

        // 仅当在"关于"页面长按大图标解锁专家模式后，方才显示此卡片
        if (expertMode) {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                SettingsNavRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.settings_group_expert),
                    summary = stringResource(R.string.settings_group_expert_summary),
                    trailing = stringResource(R.string.settings_expert_unlocked),
                    onClick = onOpenExpert,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsNavRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.about_title),
                summary = stringResource(R.string.settings_about_summary),
                onClick = onOpenAbout,
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
    SavedEffect(state.savedAt, savedMessage, snackbarHostState, state.saveError ?: state.settingsError)

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_model_service),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { SettingsSaveAction(onClick = actions.onSave, enabled = !state.isLoading && !state.isSaving) },
    ) {
        SettingsDraftStatus(state)
        if (state.isLoading || state.isResetting) return@SettingsPageScaffold
        AppText(stringResource(R.string.settings_service_workflow), style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        // ---- provider ----
        var choosingProvider by rememberSaveable(state.provider) { mutableStateOf(state.baseUrl.isBlank()) }
        SettingsGroupLabel(stringResource(R.string.settings_section_provider))
        SettingsCard {
            SettingsBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val providerLabel = if (state.provider == LlmProvider.CUSTOM)
                        stringResource(R.string.settings_provider_custom) else state.provider.gridLabel
                    AppText(stringResource(R.string.settings_current_provider, providerLabel),
                        style = AppTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    AppTextButton(stringResource(if (choosingProvider) R.string.settings_provider_collapse
                        else R.string.settings_provider_change), { choosingProvider = !choosingProvider })
                }
                if (choosingProvider) {
                    Spacer(Modifier.height(8.dp))
                    ProviderGrid(selected = state.provider, onSelect = {
                        actions.onProviderChange(it)
                        choosingProvider = false
                    })
                }
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
                    supportingText = state.baseUrlError?.let { errorText(it) }
                        ?: stringResource(R.string.settings_base_url_hint),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        text = stringResource(R.string.settings_model_manual_hint),
                        style = AppTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    AppOutlinedButton(
                        onClick = actions.onFetchModels,
                        enabled = !state.isFetchingModels && !state.isLoading && state.baseUrl.isNotBlank(),
                    ) {
                        if (state.isFetchingModels) {
                            AppCircularProgressIndicator(size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            AppText(stringResource(R.string.settings_models_fetching), style = AppTheme.typography.labelSmall)
                        } else {
                            AppIcon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            AppText(stringResource(R.string.settings_models_fetch), style = AppTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                AppTextField(
                    value = state.model,
                    onValueChange = actions.onModelChange,
                    label = stringResource(R.string.settings_model),
                    singleLine = true,
                    isError = state.modelError != null,
                    supportingText = state.modelError?.let { errorText(it) }
                        ?: state.fetchModelsError
                        ?: state.fetchModelsSuccessMessage,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 1. 联网拉取到的实时可用模型
                if (state.fetchedModels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AppText(
                        text = stringResource(R.string.settings_models_fetched_title, state.fetchedModels.size),
                        style = AppTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    var modelSearch by remember(state.fetchedModels) { mutableStateOf("") }
                    AppTextField(value = modelSearch, onValueChange = { modelSearch = it },
                        label = stringResource(R.string.settings_models_search), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth())
                    val matches = remember(state.fetchedModels, modelSearch) {
                        state.fetchedModels.filter { it.contains(modelSearch, ignoreCase = true) }
                    }
                    if (matches.isEmpty()) AppText(stringResource(R.string.settings_models_no_match),
                        style = AppTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(matches, key = { it }) { name ->
                            SelectableCell(label = name, isSelected = state.model.trim() == name,
                                onClick = { actions.onModelChange(name) }, height = 48, corner = 8,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // 2. 预设建议模型
                val suggestions = state.provider.suggestedModels
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    AppText(
                        text = stringResource(R.string.settings_model_quick_select),
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { name ->
                            val isSelected = state.model.trim() == name
                            SelectableCell(
                                label = name,
                                isSelected = isSelected,
                                onClick = { actions.onModelChange(name) },
                                height = 48,
                                corner = 6,
                            )
                        }
                    }
                }
            }

            SettingsRowDivider()

            SettingsBlock {
                AppTextField(
                    value = state.timeoutSecondsInput ?: state.timeoutSeconds.toString(),
                    onValueChange = actions.onTimeoutSecondsInputChange,
                    label = stringResource(R.string.settings_timeout_seconds),
                    isError = state.timeoutError != null,
                    supportingText = state.timeoutError ?: stringResource(R.string.settings_timeout_seconds_summary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            enabled = !state.isLoading && state.testState !is TestState.Running && !state.diagnosticsRunning,
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

        Spacer(Modifier.height(12.dp))
        AppOutlinedButton(
            onClick = actions.onRunDiagnostics,
            enabled = !state.isLoading && !state.diagnosticsRunning && state.testState !is TestState.Running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            AppText(stringResource(if (state.diagnosticsRunning) R.string.settings_testing else R.string.settings_diagnostics))
        }
        AppText(stringResource(R.string.settings_diagnostics_hint), style = AppTheme.typography.bodySmall)
        state.diagnostics.values.forEach { result ->
            val label = stringResource(when (result.kind) {
                com.paw.agent.core.llm.DiagnosticKind.TEXT -> R.string.settings_diagnostic_text
                com.paw.agent.core.llm.DiagnosticKind.STREAM -> R.string.settings_diagnostic_stream
                com.paw.agent.core.llm.DiagnosticKind.TOOLS -> R.string.settings_diagnostic_tools
                com.paw.agent.core.llm.DiagnosticKind.VISION -> R.string.settings_diagnostic_vision
            })
            val status = when (result.status) {
                com.paw.agent.core.llm.DiagnosticStatus.RUNNING -> "…"
                com.paw.agent.core.llm.DiagnosticStatus.PASSED -> "✓"
                com.paw.agent.core.llm.DiagnosticStatus.FAILED -> "✕"
            }
            AppText("$status $label ${result.detail}", style = AppTheme.typography.bodySmall)
        }

        SettingsResetAction(actions.onReset, enabled = !state.isLoading && !state.isSaving)

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
    SavedEffect(state.savedAt, savedMessage, snackbarHostState, state.saveError ?: state.settingsError)

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_generation),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { SettingsSaveAction(onClick = actions.onSave, enabled = !state.isLoading && !state.isSaving) },
    ) {
        SettingsDraftStatus(state)
        if (state.isLoading || state.isResetting) return@SettingsPageScaffold
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
                    value = state.maxTokensInput ?: state.maxTokens.takeIf { it > 0 }?.toString().orEmpty(),
                    onValueChange = actions.onMaxTokensInputChange,
                    label = stringResource(R.string.settings_max_tokens),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.tokenError?.let { AppText(errorText(it), color = AppTheme.colors.error) }
            }

            SettingsRowDivider()

            SettingsBlock {
                AppTextField(
                    value = state.maxTotalTokensInput ?: state.maxTotalTokens.takeIf { it > 0 }?.toString().orEmpty(),
                    onValueChange = actions.onMaxTotalTokensInputChange,
                    label = stringResource(R.string.settings_max_total_tokens),
                    supportingText = stringResource(R.string.settings_max_total_tokens_hint),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.maxTotalTokens == 0) {
                    val fromProvider = state.detectedFor == "${state.baseUrl.trim()}|${state.model.trim()}" &&
                        state.detectedTotalTokens > 0
                    AppText(stringResource(
                        if (fromProvider) R.string.settings_provider_total_tokens else R.string.settings_detected_total_tokens,
                        state.toConfig().effectiveMaxTotalTokens,
                    ))
                }
            }

            SettingsRowDivider()
            SettingsBlock {
                AppText(stringResource(R.string.settings_reasoning_effort))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    com.paw.agent.core.llm.LlmConfig.REASONING_EFFORTS.forEach { effort ->
                        AppTextButton(
                            text = if (state.reasoningEffort == effort) "✓ $effort" else effort,
                            onClick = { actions.onReasoningEffortChange(effort) },
                        )
                    }
                }
                AppText(stringResource(R.string.settings_reasoning_hint))
            }

            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_compress),
                subtitle = stringResource(R.string.settings_auto_compress_hint),
                checked = state.autoCompress,
                onCheckedChange = actions.onAutoCompressChange,
            )
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
    SavedEffect(state.savedAt, savedMessage, snackbarHostState, state.saveError ?: state.settingsError)

    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
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
    val hasNotificationPermission = remember(permRefreshTick.intValue) {
        com.paw.agent.device.DevicePermissionManager.hasNotificationPermission(context)
    }
    val requestNotifications = com.paw.agent.ui.components.rememberNotificationPermissionAction {
        permRefreshTick.intValue++
    }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val isRootGranted by produceState(initialValue = false, permRefreshTick.intValue) {
        value = withContext(Dispatchers.IO) {
            com.paw.agent.device.DevicePermissionManager.isRootAvailable()
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_agent),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { SettingsSaveAction(onClick = actions.onSave, enabled = !state.isLoading && !state.isSaving) },
    ) {
        SettingsDraftStatus(state)
        if (state.isLoading || state.isResetting) return@SettingsPageScaffold
        SettingsGroupLabel(stringResource(R.string.settings_group_execution))
        SettingsCard {
            SettingsBlock {
                StepSettingsControl(
                    maxToolRounds = state.maxToolRounds,
                    rawInput = state.maxToolRoundsInput,
                    inputError = state.maxToolRoundsError,
                    onInputChange = actions.onMaxToolRoundsInputChange,
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

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            AppCard(
                cornerRadius = 12.dp,
                containerColor = AppTheme.colors.surfaceContainerHigh,
                contentColor = AppTheme.colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    AppText(
                        text = stringResource(R.string.settings_device_compatibility, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT),
                        style = AppTheme.typography.bodySmall,
                    )
                }
            }
        }

        SettingsGroupLabel(stringResource(R.string.settings_execution_channels))
        SettingsCard {
            PermissionRow(
                title = stringResource(R.string.permission_root),
                subtitle = stringResource(R.string.permission_root_summary),
                isGranted = isRootGranted,
                statusText = if (isRootGranted) {
                    stringResource(R.string.permission_root_granted)
                } else {
                    stringResource(R.string.permission_root_not_granted)
                },
                actionText = if (!isRootGranted) {
                    stringResource(R.string.permission_root_request)
                } else if (!isAccessibilityEnabled) {
                    stringResource(R.string.settings_root_accessibility)
                } else null,
                onAction = {
                    coroutineScope.launch {
                        if (!isRootGranted) {
                            val ok = com.paw.agent.device.DevicePermissionManager.requestOrTestRoot()
                            permRefreshTick.intValue++
                            if (ok) {
                                snackbarHostState.showSnackbar(resources.getString(R.string.settings_root_success))
                            } else {
                                snackbarHostState.showSnackbar(resources.getString(R.string.settings_root_failure))
                            }
                        } else if (!isAccessibilityEnabled) {
                            val ok = com.paw.agent.device.DevicePermissionManager.enableAccessibilityViaRoot(context)
                            permRefreshTick.intValue++
                            if (ok) {
                                snackbarHostState.showSnackbar(resources.getString(R.string.settings_accessibility_success))
                            } else {
                                snackbarHostState.showSnackbar(resources.getString(R.string.settings_accessibility_failure))
                            }
                        }
                    }
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

            SettingsRowDivider()

            PermissionRow(
                title = stringResource(R.string.permission_notification),
                subtitle = stringResource(R.string.permission_notification_summary),
                isGranted = hasNotificationPermission,
                statusText = if (hasNotificationPermission) {
                    stringResource(R.string.permission_granted)
                } else {
                    stringResource(R.string.permission_not_granted)
                },
                actionText = stringResource(R.string.permission_open_settings),
                onAction = requestNotifications,
            )
        }

        val isXiaomiDevice = remember {
            com.paw.agent.device.DevicePermissionManager.isXiaomiOrHyperOs()
        }
        if (isXiaomiDevice) {
            Spacer(Modifier.height(20.dp))
            SettingsGroupLabel(stringResource(R.string.settings_hyperos_title))
            AppCard(
                cornerRadius = 12.dp,
                containerColor = AppTheme.colors.surfaceContainerHigh,
                contentColor = AppTheme.colors.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AppText(
                        text = stringResource(R.string.settings_hyperos_help),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    AppText(
                        text = stringResource(R.string.settings_hyperos_restricted_title),
                        style = AppTheme.typography.titleSmall,
                    )
                    AppText(
                        text = stringResource(R.string.settings_hyperos_restricted_help),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    AppOutlinedButton(
                        onClick = { com.paw.agent.device.DevicePermissionManager.openAppDetailsSettings(context) },
                    ) {
                        AppText(stringResource(R.string.settings_open_app_details), style = AppTheme.typography.labelSmall)
                    }

                    Spacer(Modifier.height(14.dp))

                    AppText(
                        text = stringResource(R.string.settings_hyperos_overlay_title),
                        style = AppTheme.typography.titleSmall,
                    )
                    AppText(
                        text = stringResource(R.string.settings_hyperos_overlay_help),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    AppOutlinedButton(
                        onClick = { com.paw.agent.device.DevicePermissionManager.openXiaomiPermissionsSettings(context) },
                    ) {
                        AppText(stringResource(R.string.settings_open_permission_manager), style = AppTheme.typography.labelSmall)
                    }

                    Spacer(Modifier.height(14.dp))

                    AppText(
                        text = stringResource(R.string.settings_hyperos_battery_title),
                        style = AppTheme.typography.titleSmall,
                    )
                    AppText(
                        text = stringResource(R.string.settings_hyperos_battery_help),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    AppText(
                        text = stringResource(R.string.settings_hyperos_debug_title),
                        style = AppTheme.typography.titleSmall,
                    )
                    AppText(
                        text = stringResource(R.string.settings_hyperos_debug_help),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    AppOutlinedButton(
                        onClick = { com.paw.agent.device.DevicePermissionManager.openDeveloperOptions(context) },
                    ) {
                        AppText(stringResource(R.string.settings_open_developer_options), style = AppTheme.typography.labelSmall)
                    }
                }
            }
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
        state.saveError?.let { ResultBanner(it, isError = true) }
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
    error: String? = null,
) {
    LaunchedEffect(error) { error?.let { hostState.showSnackbar(it) } }
    var lastShown by remember { mutableStateOf(savedAt) }
    LaunchedEffect(savedAt) {
        val at = savedAt ?: return@LaunchedEffect
        if (at == lastShown) return@LaunchedEffect
        lastShown = at
        hostState.showSnackbar(message)
    }
}

@Composable
private fun SettingsDraftStatus(state: LlmSettingsUiState) {
    val label = when {
        state.isLoading -> R.string.settings_loading
        state.isResetting -> R.string.settings_resetting
        state.isSaving -> R.string.settings_saving
        state.hasUnsavedChanges -> R.string.settings_unsaved
        else -> null
    }
    label?.let {
        AppText(stringResource(it), style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.primary, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun SettingsResetAction(onReset: () -> Unit, enabled: Boolean) {
    var confirming by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    AppTextButton(text = stringResource(R.string.settings_reset), onClick = { confirming = true },
        enabled = enabled, color = AppTheme.colors.error)
    if (confirming) AppAlertDialog(
        onDismissRequest = { confirming = false },
        title = { AppText(stringResource(R.string.settings_reset)) },
        text = { AppText(stringResource(R.string.settings_reset_confirmation)) },
        confirmButton = { AppTextButton(stringResource(R.string.settings_reset), {
            confirming = false
            onReset()
        }, enabled = enabled, color = AppTheme.colors.error) },
        dismissButton = { AppTextButton(stringResource(android.R.string.cancel), { confirming = false }) },
    )
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
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
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
    rawInput: String?,
    inputError: String?,
    onInputChange: (String) -> Unit,
    onMaxToolRoundsChange: (Int) -> Unit,
) {
    val isUnlimited = maxToolRounds == 0
    val boundedRounds = maxToolRounds.coerceAtLeast(0)
    // 计数规则：每 5 步记为 1 格
    val currentGrids = if (boundedRounds % 5 == 0) {
        stringResource(R.string.settings_max_rounds_grids, boundedRounds / 5)
    } else {
        stringResource(R.string.settings_rounds_fraction, boundedRounds / 5.0)
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
                modifier = Modifier.widthIn(max = 180.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (isUnlimited) AppTheme.colors.tertiaryContainer else AppTheme.colors.primaryContainer,
                contentColor = if (isUnlimited) AppTheme.colors.onTertiaryContainer else AppTheme.colors.onPrimaryContainer,
            ) {
                AppText(
                    text = if (maxToolRounds < 0) {
                        stringResource(R.string.settings_rounds_invalid)
                    } else if (isUnlimited) {
                        stringResource(R.string.settings_max_rounds_unlimited)
                    } else {
                        stringResource(R.string.settings_max_rounds_value, boundedRounds, currentGrids)
                    },
                    style = AppTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

        // 自定义步数输入框 (仅允许正整数或0，0表示不限制)
        AppTextField(
            value = rawInput ?: boundedRounds.toString(),
            onValueChange = onInputChange,
            isError = inputError != null,
            label = stringResource(R.string.settings_max_rounds_custom),
            placeholder = stringResource(R.string.settings_max_rounds_custom_hint),
            supportingText = inputError ?: run {
                val num = boundedRounds
                if (num <= 0) {
                    stringResource(R.string.settings_max_rounds_unlimited_desc)
                } else {
                    val g = if (num % 5 == 0) {
                        stringResource(R.string.settings_max_rounds_grids, num / 5)
                    } else {
                        stringResource(R.string.settings_rounds_fraction, num / 5.0)
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
        val presets = listOf(
            0 to R.string.settings_max_rounds_preset_0,
            10 to R.string.settings_max_rounds_preset_10,
            15 to R.string.settings_max_rounds_preset_15,
            25 to R.string.settings_max_rounds_preset_25,
            50 to R.string.settings_max_rounds_preset_50,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowItems.forEach { (stepVal, labelRes) ->
                        val isSelected = boundedRounds == stepVal
                        SelectableCell(
                            label = stringResource(labelRes),
                            isSelected = isSelected,
                            onClick = { onMaxToolRoundsChange(stepVal) },
                            modifier = Modifier.weight(1f),
                            height = 48,
                            corner = 8,
                        )
                    }
                    if (rowItems.size < 3) {
                        Spacer(Modifier.weight((3 - rowItems.size).toFloat()))
                    }
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
    "error_total_tokens_range" -> stringResource(R.string.error_total_tokens_range)
    "error_top_p_range" -> stringResource(R.string.error_top_p_range)
    else -> key
}

private fun formatTwoDecimals(value: Float): String =
    ((value * 100).roundToInt() / 100f).toString()
