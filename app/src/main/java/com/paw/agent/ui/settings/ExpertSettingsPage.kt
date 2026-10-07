package com.paw.agent.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import com.paw.agent.R
import com.paw.agent.device.DevicePermissionManager
import com.paw.agent.ui.components.adaptive.AppSnackbarHostState
import kotlinx.coroutines.launch

@Composable
fun ExpertSettingsPage(
    state: LlmSettingsUiState,
    actions: LlmSettingsActions,
    onBack: () -> Unit,
    snackbarHostState: AppSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permTick = remember { mutableIntStateOf(0) }

    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        permTick.intValue++
    }

    val isRootGranted = remember(permTick.intValue) {
        DevicePermissionManager.isRootAvailable()
    }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_expert),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        // ---- 视觉与语言分离显示模式 ----
        SettingsGroupLabel("交互与视图模式")
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
        SettingsGroupLabel("ROOT 高级执行通道")
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
                        val msg = if (ok) "已成功检测并获取 ROOT 权限！" else "未能获取 ROOT 权限，请确认设备已 ROOT 并授权"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- AI 自适应操作节奏 ----
        SettingsGroupLabel("操作节奏智能识别")
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_adaptive_pacing),
                subtitle = stringResource(R.string.settings_adaptive_pacing_summary),
                checked = state.adaptivePacingEnabled,
                onCheckedChange = actions.onToggleAdaptivePacing,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
