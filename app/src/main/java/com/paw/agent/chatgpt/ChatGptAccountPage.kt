package com.paw.agent.chatgpt

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.settings.SettingsBlock
import com.paw.agent.ui.settings.SettingsCard
import com.paw.agent.ui.settings.SettingsPageScaffold
import com.paw.agent.ui.theme.AppTheme

@Composable
internal fun ChatGptAccountPage(
    state: ChatGptAuthState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(stringResource(R.string.chatgpt_title), onBack, modifier) {
        SettingsCard {
            SettingsBlock {
                AppText(stringResource(R.string.chatgpt_intro), style = AppTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            SettingsBlock {
                AppText(if (state.signedIn) stringResource(R.string.chatgpt_connected,
                    state.accountLabel.ifBlank { stringResource(R.string.chatgpt_account_authorized) })
                else stringResource(R.string.chatgpt_disconnected), style = AppTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                AppText(stringResource(when (state.status) {
                    ChatGptAuthStatus.IDLE -> R.string.chatgpt_idle
                    ChatGptAuthStatus.WAITING -> R.string.chatgpt_waiting
                    ChatGptAuthStatus.SIGNED_IN -> R.string.chatgpt_success
                    ChatGptAuthStatus.CANCELLED -> R.string.chatgpt_cancelled
                    ChatGptAuthStatus.FAILED -> R.string.chatgpt_failed
                    ChatGptAuthStatus.SIGNED_OUT -> R.string.chatgpt_signed_out
                    ChatGptAuthStatus.REMOTE_UNCONFIRMED -> R.string.chatgpt_remote_unconfirmed
                }), style = AppTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        AppButton(onLogin, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            AppText(stringResource(R.string.chatgpt_continue), color = AppTheme.colors.onPrimary)
        }
        Spacer(Modifier.height(8.dp))
        AppOutlinedButton(onSignOut, enabled = state.signedIn && !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            AppText(stringResource(R.string.chatgpt_logout))
        }
        Spacer(Modifier.height(8.dp))
        AppOutlinedButton(onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            AppText(stringResource(R.string.chatgpt_back))
        }
    }
}
