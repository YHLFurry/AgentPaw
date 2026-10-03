package com.paw.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.core.llm.LlmProvider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsScreen(
    state: LlmSettingsUiState,
    onBack: () -> Unit,
    onProviderChange: (LlmProvider) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onStreamChange: (Boolean) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.settings_saved)

    LaunchedEffect(state.savedAt) {
        if (state.savedAt != null) snackbarHostState.showSnackbar(savedMessage)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ---- provider ----
            SettingsSection(title = stringResource(R.string.settings_section_provider)) {
                Text(
                    text = stringResource(R.string.settings_provider),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ProviderChips(
                    selected = state.provider,
                    onSelect = onProviderChange,
                )
            }

            // ---- connection ----
            SettingsSection(title = stringResource(R.string.settings_section_connection)) {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    singleLine = true,
                    isError = state.baseUrlError != null,
                    supportingText = state.baseUrlError?.let { { Text(errorText(it)) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    isError = state.apiKeyError != null,
                    supportingText = {
                        Text(
                            text = state.apiKeyError?.let { errorText(it) }
                                ?: stringResource(R.string.settings_api_key_stored),
                        )
                    },
                    visualTransformation = if (state.showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = onToggleApiKeyVisibility) {
                            Icon(
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
                OutlinedTextField(
                    value = state.model,
                    onValueChange = onModelChange,
                    label = { Text(stringResource(R.string.settings_model)) },
                    singleLine = true,
                    isError = state.modelError != null,
                    supportingText = state.modelError?.let { { Text(errorText(it)) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                SwitchRow(
                    title = stringResource(R.string.settings_stream),
                    subtitle = stringResource(R.string.settings_stream_summary),
                    checked = state.stream,
                    onCheckedChange = onStreamChange,
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
                    onValueChange = onTemperatureChange,
                )

                SliderRow(
                    label = stringResource(R.string.settings_top_p),
                    value = state.topP,
                    valueRange = 0f..1f,
                    steps = 19,
                    display = formatTwoDecimals(state.topP),
                    onValueChange = onTopPChange,
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.maxTokens.toString(),
                    onValueChange = { onMaxTokensChange(it.filter(Char::isDigit).toIntOrNull() ?: 0) },
                    label = { Text(stringResource(R.string.settings_max_tokens)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- system prompt ----
            SettingsSection(title = stringResource(R.string.settings_section_system_prompt)) {
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text(stringResource(R.string.settings_system_prompt)) },
                    supportingText = { Text(stringResource(R.string.settings_system_prompt_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- actions ----
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save))
                }

                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = state.testState !is TestState.Running,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.testState is TestState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_testing))
                    } else {
                        Text(stringResource(R.string.settings_test_connection))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TestResultBanner(state.testState)

            TextButton(
                onClick = onReset,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(R.string.settings_reset),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Column(content = content)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
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
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Text(
            text = provider.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = display,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TestResultBanner(testState: TestState) {
    val (message, isError) = when (testState) {
        is TestState.Success -> testState.message to false
        is TestState.Failure -> testState.message to true
        TestState.Idle, TestState.Running -> return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
            modifier = Modifier.padding(12.dp),
        )
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
