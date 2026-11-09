package com.paw.agent.chatgpt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.paw.agent.AgentPawApplication
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.ui.theme.AgentPawAppTheme
import kotlinx.coroutines.launch

class ChatGptLoginActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[ChatGptLoginViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AgentPawApplication).container
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings.Default)
            val state by model.state.collectAsStateWithLifecycle()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !settings.darkTheme
                    isAppearanceLightNavigationBars = !settings.darkTheme
                }
            }
            AgentPawAppTheme(settings.uiTheme, settings.darkTheme, settings.dynamicColor) {
                ChatGptAccountPage(state, model::login, model::signOut,
                    onBack = { model.cancelLogin(); finish() })
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.browserRequests.collect { attempt ->
                    if (attempt.isCurrent) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attempt.url))) }
                        catch (_: Exception) { model.browserFailed(attempt) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) model.cancelLogin()
        super.onDestroy()
    }
}
