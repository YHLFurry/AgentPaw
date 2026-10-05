package com.paw.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.ui.AgentPawApp
import com.paw.agent.ui.theme.AgentPawTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as AgentPawApplication).container

        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings.Default)

            val executionState by com.paw.agent.ui.floating.AgentExecutionController.state
                .collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current

            androidx.compose.runtime.LaunchedEffect(executionState.isRunning) {
                if (executionState.isRunning) {
                    com.paw.agent.ui.floating.AgentFloatingService.start(context)
                }
            }

            SyncSystemBarAppearance(darkTheme = settings.darkTheme)

            AgentPawTheme(
                darkTheme = settings.darkTheme,
                dynamicColor = settings.dynamicColor,
            ) {
                AgentPawApp(container = container)
            }
        }
    }
}

/**
 * Keeps the status bar icons legible against the current theme.
 * Called from the composition so it re-runs when the theme flips.
 */
@Composable
private fun SyncSystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
