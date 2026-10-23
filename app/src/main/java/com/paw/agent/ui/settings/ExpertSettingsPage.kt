package com.paw.agent.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.paw.agent.R
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.data.settings.HighRiskToggle
import com.paw.agent.device.DevicePermissionManager
import com.paw.agent.ui.components.adaptive.AppAlertDialog
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppSnackbarHostState
import com.paw.agent.ui.components.adaptive.AppSwitch
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextButton
import com.paw.agent.ui.theme.AppTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.ceil

/**
 * 资深（专家）设置。
 *
 * 高危开关不是普通开关：每一个在开启前都会弹出**具体后果**说明，并要求用户
 * 主动勾选「我已了解风险」。存在前置条件的开关（公网明文依赖 HTTP、Root 命令
 * 依赖 ROOT 模式）在条件不满足时直接禁用并说明原因，而不是让用户点了没反应。
 */
@Composable
fun ExpertSettingsPage(
    state: LlmSettingsUiState,
    actions: LlmSettingsActions,
    onBack: () -> Unit,
    snackbarHostState: AppSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val permTick = remember { mutableIntStateOf(0) }
    var pendingRisk by remember { mutableStateOf<HighRiskToggle?>(null) }
    var pendingBypass by remember { mutableStateOf(false) }

    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        permTick.intValue++
    }

    LaunchedEffect(state.riskNotice) {
        state.riskNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            actions.onConsumeRiskNotice()
        }
    }

    val isRootGranted by produceState(initialValue = false, permTick.intValue) {
        value = withContext(Dispatchers.IO) {
            DevicePermissionManager.isRootAvailable()
        }
    }

    // 低频心跳：用户停留在本页时，“剩余 N 分钟”也能自然衰减，而不是定格在进入时的值。
    var clockTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.riskBypassUntil) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            clockTick++
        }
    }
    val bypassActive = remember(clockTick, state.riskBypassUntil) {
        state.riskBypassUntil > System.currentTimeMillis()
    }
    val bypassMinutesLeft = if (bypassActive) {
        ceil((state.riskBypassUntil - System.currentTimeMillis()) / 60_000.0).toInt().coerceAtLeast(1)
    } else {
        0
    }
    val activeHighRisk = listOf(
        state.allowHttpTransport,
        state.allowPublicHttp,
        state.allowShellSkills,
        state.allowSensitiveScreen,
        bypassActive,
    ).count { it }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_expert),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        // ---- 视觉与语言分离显示模式 ----
        SettingsGroupLabel(stringResource(R.string.settings_interaction_group))
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_split_vision_language),
                subtitle = stringResource(R.string.settings_split_vision_language_summary),
                checked = state.splitVisionLanguageMode,
                onCheckedChange = actions.onToggleSplitVisionLanguage,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- ROOT 模式与提权 ----
        SettingsGroupLabel(stringResource(R.string.settings_root_group))
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_root_mode),
                subtitle = stringResource(R.string.settings_root_mode_summary),
                checked = state.rootModeEnabled,
                onCheckedChange = actions.onToggleRootMode,
            )

            SettingsRowDivider()

            PermissionRow(
                title = stringResource(R.string.permission_root),
                subtitle = stringResource(R.string.permission_root_summary),
                isGranted = isRootGranted,
                statusText = if (isRootGranted) {
                    stringResource(R.string.permission_root_granted)
                } else {
                    stringResource(R.string.permission_root_not_granted)
                },
                actionText = stringResource(R.string.permission_root_request),
                onAction = {
                    scope.launch {
                        val ok = DevicePermissionManager.requestOrTestRoot()
                        permTick.intValue++
                        val msg = if (ok) resources.getString(R.string.settings_root_confirmed) else resources.getString(R.string.settings_root_denied)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- AI 自适应操作节奏 ----
        SettingsGroupLabel(stringResource(R.string.settings_pacing_group))
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_adaptive_pacing),
                subtitle = stringResource(R.string.settings_adaptive_pacing_summary),
                checked = state.adaptivePacingEnabled,
                onCheckedChange = actions.onToggleAdaptivePacing,
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- 高危功能开关 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = AppTheme.colors.error,
                modifier = Modifier.width(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            SettingsGroupLabel(stringResource(R.string.settings_high_risk_group))
        }

        if (activeHighRisk > 0) {
            DangerBanner(stringResource(R.string.settings_high_risk_active_banner, activeHighRisk))
            Spacer(Modifier.height(10.dp))
        }

        AppText(
            text = stringResource(R.string.settings_high_risk_intro),
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )

        SettingsCard {
            HighRiskSwitchRow(
                title = stringResource(R.string.settings_allow_http),
                summary = stringResource(R.string.settings_allow_http_summary),
                checked = state.allowHttpTransport,
                onCheckedChange = { enabled ->
                    if (enabled) pendingRisk = HighRiskToggle.ALLOW_HTTP
                    else actions.onToggleHighRisk(HighRiskToggle.ALLOW_HTTP, false)
                },
            )

            SettingsRowDivider()

            HighRiskSwitchRow(
                title = stringResource(R.string.settings_allow_public_http),
                summary = if (state.allowHttpTransport) {
                    stringResource(R.string.settings_allow_public_http_summary)
                } else {
                    stringResource(R.string.settings_root_required).let {
                        stringResource(R.string.settings_allow_public_http_summary) + " · " + it
                    }
                },
                checked = state.allowPublicHttp,
                enabled = state.allowHttpTransport,
                onCheckedChange = { enabled ->
                    if (enabled) pendingRisk = HighRiskToggle.ALLOW_PUBLIC_HTTP
                    else actions.onToggleHighRisk(HighRiskToggle.ALLOW_PUBLIC_HTTP, false)
                },
            )

            SettingsRowDivider()

            HighRiskSwitchRow(
                title = stringResource(R.string.settings_allow_shell_skills),
                summary = if (state.rootModeEnabled) {
                    stringResource(R.string.settings_allow_shell_skills_summary)
                } else {
                    stringResource(R.string.settings_allow_shell_skills_summary) + " · " +
                        stringResource(R.string.settings_root_required)
                },
                checked = state.allowShellSkills,
                enabled = state.rootModeEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) pendingRisk = HighRiskToggle.ALLOW_SHELL_SKILLS
                    else actions.onToggleHighRisk(HighRiskToggle.ALLOW_SHELL_SKILLS, false)
                },
            )

            SettingsRowDivider()

            HighRiskSwitchRow(
                title = stringResource(R.string.settings_allow_sensitive_screen),
                summary = stringResource(R.string.settings_allow_sensitive_screen_summary),
                checked = state.allowSensitiveScreen,
                onCheckedChange = { enabled ->
                    if (enabled) pendingRisk = HighRiskToggle.ALLOW_SENSITIVE_SCREEN
                    else actions.onToggleHighRisk(HighRiskToggle.ALLOW_SENSITIVE_SCREEN, false)
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 临时跳过风险确认（带有效期） ----
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        AppText(
                            text = stringResource(R.string.settings_risk_bypass),
                            style = AppTheme.typography.bodyLarge,
                            color = AppTheme.colors.onSurface,
                        )
                        AppText(
                            text = if (bypassActive) {
                                stringResource(R.string.settings_risk_bypass_active, bypassMinutesLeft)
                            } else {
                                stringResource(R.string.settings_risk_bypass_off)
                            },
                            style = AppTheme.typography.bodySmall,
                            color = if (bypassActive) AppTheme.colors.error else AppTheme.colors.onSurfaceVariant,
                            fontWeight = if (bypassActive) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                    if (bypassActive) {
                        AppTextButton(
                            text = stringResource(R.string.settings_risk_bypass_stop),
                            onClick = actions.onStopRiskBypass,
                            color = AppTheme.colors.primary,
                        )
                    } else {
                        AppTextButton(
                            text = stringResource(R.string.settings_risk_bypass_action),
                            onClick = { pendingBypass = true },
                            color = AppTheme.colors.error,
                        )
                    }
                }
                AppText(
                    text = stringResource(
                        R.string.settings_risk_bypass_summary,
                        AppSettings.MAX_RISK_BYPASS_MINUTES,
                    ),
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    pendingRisk?.let { toggle ->
        val titleRes = when (toggle) {
            HighRiskToggle.ALLOW_HTTP -> R.string.settings_allow_http
            HighRiskToggle.ALLOW_PUBLIC_HTTP -> R.string.settings_allow_public_http
            HighRiskToggle.ALLOW_SHELL_SKILLS -> R.string.settings_allow_shell_skills
            HighRiskToggle.ALLOW_SENSITIVE_SCREEN -> R.string.settings_allow_sensitive_screen
        }
        val riskRes = when (toggle) {
            HighRiskToggle.ALLOW_HTTP -> R.string.risk_http
            HighRiskToggle.ALLOW_PUBLIC_HTTP -> R.string.risk_public_http
            HighRiskToggle.ALLOW_SHELL_SKILLS -> R.string.risk_shell
            HighRiskToggle.ALLOW_SENSITIVE_SCREEN -> R.string.risk_sensitive
        }
        HighRiskConfirmDialog(
            title = stringResource(titleRes),
            risk = stringResource(riskRes),
            onDismiss = { pendingRisk = null },
            onConfirm = {
                actions.onToggleHighRisk(toggle, true)
                pendingRisk = null
            },
        )
    }

    if (pendingBypass) {
        HighRiskConfirmDialog(
            title = stringResource(R.string.settings_risk_bypass),
            risk = stringResource(R.string.risk_bypass),
            onDismiss = { pendingBypass = false },
            onConfirm = {
                actions.onStartRiskBypass()
                pendingBypass = false
            },
        )
    }
}

/** 高危开关行：标题前有红色警示点，比普通开关更容易被识别为「危险区」。 */
@Composable
private fun HighRiskSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = "●",
                    style = AppTheme.typography.labelSmall,
                    color = if (checked) AppTheme.colors.error else AppTheme.colors.outline,
                )
                Spacer(Modifier.width(6.dp))
                AppText(
                    text = title,
                    style = AppTheme.typography.bodyLarge,
                    color = if (enabled) AppTheme.colors.onSurface else AppTheme.colors.onSurfaceVariant,
                )
            }
            AppText(
                text = summary,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        AppSwitch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
        )
    }
}

@Composable
private fun DangerBanner(text: String) {
    AppSurface(
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.errorContainer,
        contentColor = AppTheme.colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                imageVector = Icons.Outlined.AdminPanelSettings,
                contentDescription = null,
                tint = AppTheme.colors.onErrorContainer,
                modifier = Modifier.width(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            AppText(
                text = text,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onErrorContainer,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 高危开关确认对话框。
 *
 * 确认按钮在勾选「我已了解风险」之前保持禁用——这是“启用约束”的可交互部分，
 * 而不是只写一段没人看的说明。
 */
@Composable
private fun HighRiskConfirmDialog(
    title: String,
    risk: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledged by remember(title) { mutableStateOf(false) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            AppText(text = stringResource(R.string.risk_dialog_title, title))
        },
        text = {
            Column {
                AppText(
                    text = risk,
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                AppSurface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppTheme.colors.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppSwitch(
                            checked = acknowledged,
                            onCheckedChange = { acknowledged = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        AppText(
                            text = stringResource(R.string.risk_dialog_ack),
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            AppButton(onClick = onConfirm, enabled = acknowledged) {
                AppText(stringResource(R.string.risk_dialog_confirm))
            }
        },
        dismissButton = {
            AppOutlinedButton(onClick = onDismiss) {
                AppText(stringResource(R.string.action_cancel))
            }
        },
    )
}
