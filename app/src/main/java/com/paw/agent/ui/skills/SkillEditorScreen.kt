package com.paw.agent.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.R
import com.paw.agent.core.skill.SkillCatalog
import com.paw.agent.core.skill.SkillDsl
import com.paw.agent.core.skill.SkillRisk
import com.paw.agent.ui.components.adaptive.AppCircularProgressIndicator
import com.paw.agent.ui.components.adaptive.AppAlertDialog
import com.paw.agent.ui.components.adaptive.AppSnackbarHost
import com.paw.agent.ui.components.adaptive.rememberAppSnackbarHostState
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppSwitch
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextButton
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.components.adaptive.AppTopBarAction
import com.paw.agent.ui.components.adaptive.LocalTopBarClearance
import com.paw.agent.ui.settings.SettingsCard
import com.paw.agent.ui.settings.SettingsGroupLabel
import com.paw.agent.ui.settings.SettingsRowDivider
import com.paw.agent.ui.settings.SettingsSwitchRow
import com.paw.agent.ui.theme.AppTheme

/**
 * 技能编辑器。
 *
 * 同一个技能有两种等价的表示，随时切换：
 * - **简易**：一行一个动作的速记文本，元信息写在 `---` 上方；
 * - **高级**：完整 JSON，外加结构化的守卫开关与逐步启停。
 *
 * 两种模式共用同一份 [com.paw.agent.core.skill.SkillDefinition]，
 * 切换时由对方重新渲染，因此不存在“两边不一致”的中间态。
 */
@Composable
fun SkillEditorScreen(
    viewModel: SkillsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editing = state.editing
    val scrollState = rememberScrollState()
    val isContentScrolled by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
    val snackbar = rememberAppSnackbarHostState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val leaveEditor = {
        viewModel.closeEditor()
        onBack()
    }
    val requestBack = {
        if (!state.saving) {
            if (state.hasUnsavedChanges) confirmDiscard = true else leaveEditor()
        }
    }
    BackHandler(onBack = requestBack)
    LaunchedEffect(state.message) {
        state.message?.let {
            viewModel.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    if (confirmDiscard) AppAlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { AppText(stringResource(R.string.skills_discard_title)) },
        text = { AppText(stringResource(R.string.skills_discard_help)) },
        confirmButton = { AppTextButton(stringResource(R.string.skills_discard_action), {
            confirmDiscard = false
            leaveEditor()
        }) },
        dismissButton = { AppTextButton(stringResource(R.string.action_cancel), { confirmDiscard = false }) },
    )

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = if (state.isNewSkill || editing?.displayName.isNullOrBlank()) {
                    stringResource(R.string.skills_create_title)
                } else {
                    stringResource(R.string.skills_edit_title, editing?.displayName.orEmpty())
                },
                subtitle = if (state.issues.any { it.isError }) {
                    stringResource(R.string.skills_errors_count, state.issues.count { it.isError })
                } else {
                    stringResource(R.string.skills_no_errors)
                },
                isContentScrolled = isContentScrolled,
                navigationIcon = {
                    AppTopBarAction(onClick = requestBack, enabled = !state.saving) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    AppTextButton(
                        text = stringResource(if (state.saving) R.string.settings_saving else R.string.action_save),
                        onClick = { viewModel.save { onBack() } },
                        enabled = state.canSave,
                    )
                },
            )
        },
        containerColor = AppTheme.colors.background,
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { innerPadding ->
        if (editing == null) {
            Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                SkillEmptyHint(text = stringResource(R.string.skills_editor_empty))
                AppTextButton(stringResource(R.string.skills_create), onClick = viewModel::startCreate)
            }
            return@AppScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState)
                // 顶栏高度作为可滚动内容的顶部间距，内容从玻璃渐隐带下穿过。
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp + LocalTopBarClearance.current,
                    bottom = 12.dp,
                ),
        ) {
            ModeSwitcher(
                mode = state.editorMode,
                onModeChange = viewModel::setEditorMode,
                enabled = !state.saving,
            )

            Spacer(Modifier.height(12.dp))

            SummaryCard(
                risk = state.editingRisk,
                stepCount = editing.steps.size,
                parameterCount = editing.parameters.size,
                key = editing.key,
            )

            Spacer(Modifier.height(12.dp))

            if (state.editorMode == SkillEditorMode.SIMPLE) {
                SimpleEditor(viewModel, state.dslText, enabled = !state.saving)
            } else {
                AdvancedEditor(viewModel, state.jsonText, editing)
            }

            if (state.issues.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    Column(Modifier.padding(16.dp)) {
                        AppText(
                            text = stringResource(R.string.skills_diagnostics),
                            style = AppTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        SkillIssueList(state.issues)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                com.paw.agent.ui.components.adaptive.AppOutlinedButton(
                    onClick = { viewModel.testRun(editing) },
                    enabled = state.canSave && !state.testRunning,
                ) {
                    if (state.testRunning) {
                        AppCircularProgressIndicator()
                        Spacer(Modifier.width(6.dp))
                    } else {
                        AppIcon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    AppText(
                        text = stringResource(R.string.skills_test_run),
                        style = AppTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            state.testResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                SkillTestResultCard(result = result, onDismiss = viewModel::clearTestResult)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModeSwitcher(
    mode: SkillEditorMode,
    onModeChange: (SkillEditorMode) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip(
            text = stringResource(R.string.skills_mode_simple),
            selected = mode == SkillEditorMode.SIMPLE,
            enabled = enabled,
            onClick = { onModeChange(SkillEditorMode.SIMPLE) },
            modifier = Modifier.weight(1f),
        )
        ModeChip(
            text = stringResource(R.string.skills_mode_advanced),
            selected = mode == SkillEditorMode.ADVANCED,
            enabled = enabled,
            onClick = { onModeChange(SkillEditorMode.ADVANCED) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AppSurface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AppTheme.colors.primaryContainer else AppTheme.colors.surfaceContainer,
        contentColor = if (selected) AppTheme.colors.onPrimaryContainer else AppTheme.colors.onSurfaceVariant,
        modifier = modifier.clickableNoRipple(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            AppText(
                text = text,
                style = AppTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) AppTheme.colors.onPrimaryContainer else AppTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    risk: SkillRisk,
    stepCount: Int,
    parameterCount: Int,
    key: String,
) {
    AppSurface(
        shape = RoundedCornerShape(14.dp),
        color = AppTheme.colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                AppText(
                    text = key.ifBlank { "skill_…" },
                    style = AppTheme.typography.labelMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                AppText(
                    text = "$stepCount 步 · $parameterCount 个参数",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurface,
                )
            }
            SkillRiskBadge(risk)
        }
    }
}

@Composable
private fun SimpleEditor(viewModel: SkillsViewModel, text: String, enabled: Boolean) {
    SettingsGroupLabel(stringResource(R.string.skills_mode_simple))
    SettingsCard {
        Column(Modifier.padding(12.dp)) {
            AppText(
                text = stringResource(R.string.skills_dsl_help),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AppTextField(
                value = text,
                onValueChange = viewModel::onDslChange,
                enabled = enabled,
                minLines = 14,
                maxLines = 30,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    AppText(
        text = stringResource(R.string.skills_insert_action),
        style = AppTheme.typography.labelMedium,
        color = AppTheme.colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(SkillCatalog.actions) { spec ->
            AppSurface(
                shape = RoundedCornerShape(10.dp),
                color = AppTheme.colors.surfaceContainerHigh,
                modifier = Modifier.clickableNoRipple(enabled = enabled) {
                    viewModel.appendDslLine(SkillDsl.templateLine(spec.kind))
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(text = spec.emoji, style = AppTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    AppText(
                        text = spec.label,
                        style = AppTheme.typography.labelMedium,
                        color = AppTheme.colors.onSurface,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    AppText(
        text = stringResource(R.string.skills_dsl_cheatsheet),
        style = AppTheme.typography.labelSmall,
        color = AppTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun AdvancedEditor(
    viewModel: SkillsViewModel,
    jsonText: String,
    editing: com.paw.agent.core.skill.SkillDefinition,
) {
    val guard = editing.guard
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val canEditStructure = !uiState.saving && uiState.issues.none { it.code == "json_invalid" && it.isError }

    SettingsGroupLabel(stringResource(R.string.skills_mode_advanced))
    SettingsCard {
        Column(Modifier.padding(12.dp)) {
            AppText(
                text = stringResource(R.string.skills_json_help),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AppTextField(
                value = jsonText,
                onValueChange = viewModel::onJsonChange,
                enabled = !uiState.saving,
                minLines = 16,
                maxLines = 40,
                isError = uiState.issues.any { it.isError && it.code == "json_invalid" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SettingsGroupLabel(stringResource(R.string.skills_guard_title))
    SettingsCard {
        SettingsSwitchRow(
            title = stringResource(R.string.skills_guard_confirm),
            subtitle = stringResource(R.string.skills_guard_confirm_summary),
            checked = guard.confirmBeforeRun,
            enabled = canEditStructure,
            onCheckedChange = { value ->
                viewModel.updateGuard { it.copy(confirmBeforeRun = value) }
            },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.skills_guard_shell),
            subtitle = stringResource(R.string.skills_guard_shell_summary),
            checked = guard.allowShell,
            enabled = canEditStructure,
            onCheckedChange = { value ->
                viewModel.updateGuard { it.copy(allowShell = value) }
            },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.skills_guard_sensitive),
            subtitle = stringResource(R.string.skills_guard_sensitive_summary),
            checked = guard.allowSensitiveScreen,
            enabled = canEditStructure,
            onCheckedChange = { value ->
                viewModel.updateGuard { it.copy(allowSensitiveScreen = value) }
            },
        )
        SettingsRowDivider()
        Column(Modifier.padding(16.dp)) {
            AppTextField(
                value = guard.allowedPackages.joinToString(", "),
                enabled = canEditStructure,
                onValueChange = { raw ->
                    val packages = raw.split(',', '，')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    viewModel.updateGuard { it.copy(allowedPackages = packages) }
                },
                label = stringResource(R.string.skills_guard_packages),
                supportingText = stringResource(R.string.skills_guard_packages_summary),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            AppTextField(
                value = (guard.maxDurationMs / 1000).toString(),
                enabled = canEditStructure,
                onValueChange = { raw ->
                    val seconds = raw.filter(Char::isDigit).toLongOrNull() ?: return@AppTextField
                    viewModel.updateGuard { it.copy(maxDurationMs = (seconds * 1000).coerceIn(1_000, 600_000)) }
                },
                label = stringResource(R.string.skills_guard_timeout),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SettingsGroupLabel(stringResource(R.string.skills_steps_title))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            editing.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        text = "${index + 1}.",
                        style = AppTheme.typography.labelMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        AppText(
                            text = step.label.ifBlank { SkillCatalog.spec(step.kind).label },
                            style = AppTheme.typography.bodyMedium,
                            color = AppTheme.colors.onSurface,
                        )
                        AppText(
                            text = buildString {
                                append(SkillCatalog.spec(step.kind).label)
                                if (step.target.isNotBlank()) append("：${step.target}")
                                append(" · ${step.onError.name}")
                            },
                            style = AppTheme.typography.labelSmall,
                            color = AppTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    AppSwitch(
                        checked = step.enabled,
                        enabled = canEditStructure,
                        onCheckedChange = { value -> viewModel.toggleStep(step.id, value) },
                    )
                }
            }
        }
    }
}

/** LazyRow 里的无涟漪点击（避免选中态被水波纹盖住）。 */
@Composable
private fun Modifier.clickableNoRipple(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(
        enabled = enabled,
        interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
