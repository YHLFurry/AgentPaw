package com.paw.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paw.agent.AppContainer
import com.paw.agent.ui.chat.ChatScreen
import com.paw.agent.ui.chat.ChatViewModel
import com.paw.agent.ui.settings.LlmSettingsScreen
import com.paw.agent.ui.settings.LlmSettingsViewModel

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

@Composable
fun AgentPawApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(
            initialValue = com.paw.agent.data.settings.AppSettings.Default,
        )

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            agent = container.agent,
            conversationRepository = container.conversationRepository,
            settingsRepository = container.settingsRepository,
        ),
    )

    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
        modifier = modifier,
    ) {
        composable(Routes.CHAT) {
            val conversation by chatViewModel.conversation.collectAsStateWithLifecycle()
            val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

            ChatScreen(
                messages = conversation.messages,
                draft = uiState.draft,
                isGenerating = uiState.isGenerating,
                configReady = uiState.configReady,
                modelLabel = settings.llm.model,
                onDraftChange = chatViewModel::onDraftChange,
                onSendOrStop = { chatViewModel.onSendOrStop(settings.llm) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onNewConversation = chatViewModel::newConversation,
            )
        }

        composable(Routes.SETTINGS) {
            val settingsViewModel: LlmSettingsViewModel = viewModel(
                factory = LlmSettingsViewModel.Factory(
                    settingsRepository = container.settingsRepository,
                    llmClient = container.llmClient,
                ),
            )
            val state by settingsViewModel.uiState.collectAsStateWithLifecycle()

            LlmSettingsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onProviderChange = settingsViewModel::onProviderChange,
                onBaseUrlChange = settingsViewModel::onBaseUrlChange,
                onApiKeyChange = settingsViewModel::onApiKeyChange,
                onModelChange = settingsViewModel::onModelChange,
                onTemperatureChange = settingsViewModel::onTemperatureChange,
                onTopPChange = settingsViewModel::onTopPChange,
                onMaxTokensChange = settingsViewModel::onMaxTokensChange,
                onStreamChange = settingsViewModel::onStreamChange,
                onSystemPromptChange = settingsViewModel::onSystemPromptChange,
                onToggleApiKeyVisibility = settingsViewModel::toggleApiKeyVisibility,
                onSave = settingsViewModel::save,
                onTestConnection = settingsViewModel::testConnection,
                onReset = settingsViewModel::resetToDefaults,
            )
        }
    }
}
