package com.paw.agent.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.paw.agent.R
import com.paw.agent.device.DevicePermissionManager
import com.paw.agent.ui.components.adaptive.AppAlertDialog
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppText

/** Shared by chat and settings, including recovery after the system stops showing the prompt. */
@Composable
fun rememberNotificationPermissionAction(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult()
        showRecovery = !granted
    }
    if (showRecovery) {
        AppAlertDialog(
            onDismissRequest = { showRecovery = false },
            title = { AppText(stringResource(R.string.chat_notification_recovery_title)) },
            text = { AppText(stringResource(R.string.chat_notification_recovery_help)) },
            confirmButton = {
                AppButton(onClick = {
                    showRecovery = false
                    DevicePermissionManager.openNotificationSettings(context)
                }) { AppText(stringResource(R.string.chat_open_system_settings)) }
            },
            dismissButton = {
                AppOutlinedButton(onClick = { showRecovery = false }) {
                    AppText(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    return {
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DevicePermissionManager.openNotificationSettings(context)
        }
    }
}
