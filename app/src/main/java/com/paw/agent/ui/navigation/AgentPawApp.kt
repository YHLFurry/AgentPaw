package com.paw.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paw.agent.AppContainer
import com.paw.agent.ui.chat.ChatScreen
import com.paw.agent.ui.chat.ChatViewModel
import com.paw.agent.ui.components.adaptive.rememberAppSnackbarHostState
import com.paw.agent.ui.settings.AgentControlPage
import com.paw.agent.ui.settings.AppearanceActions
import com.paw.agent.ui.settings.AppearanceSettingsPage
import com.paw.agent.ui.settings.AppearanceSettingsViewModel
import com.paw.agent.ui.settings.GenerationPage
import com.paw.agent.ui.settings.LlmServicePage
import com.paw.agent.ui.settings.LlmSettingsActions
import com.paw.agent.ui.settings.LlmSettingsViewModel
import com.paw.agent.ui.settings.SettingsHomeScreen
import com.paw.agent.ui.settings.gridLabel
import com.paw.agent.ui.settings.themeTitleRes

object Routes {
    const val CHAT = "chat"

    /** Settings home: the list of sections. */
    const val SETTINGS = "settings"

    /** One route per settings section, one level deep. */
    const val SETTINGS_MODEL = "settings/model"
    const val SETTINGS_GENERATION = "settings/generation"
    const val SETTINGS_AGENT = "settings/agent"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_EXPERT = "settings/expert"

    const val ABOUT = "about"
    const val SKILLS = "skills"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/detail/{id}"
    const val HISTORY_COMPARE = "history/compare/{id1}/{id2}"
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

    // Keep the container's cached config current so tools that spawn a
    // sub-agent read the same settings as this turn.
    LaunchedEffect(settings.llm) {
        container.onLlmConfigChanged(settings.llm)
    }

    // Keep phone controller mode aligned with rootModeEnabled setting
    LaunchedEffect(settings.rootModeEnabled) {
        container.phoneController.controlMode = if (settings.rootModeEnabled) {
            com.paw.agent.device.PhoneControlMode.ROOT
        } else {
            com.paw.agent.device.PhoneControlMode.AUTO
        }
    }

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
                splitVisionLanguageMode = settings.splitVisionLanguageMode,
                activeBreakpoint = uiState.activeBreakpoint,
                onDraftChange = chatViewModel::onDraftChange,
                onSendOrStop = { chatViewModel.onSendOrStop(settings.llm) },
                onResumeBreakpoint = { chatViewModel.resumeBreakpoint(config = settings.llm) },
                onDismissBreakpoint = chatViewModel::dismissBreakpoint,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSkills = { navController.navigate(Routes.SKILLS) },
                onNewConversation = chatViewModel::newConversation,
            )
        }

        composable(Routes.SETTINGS) {
            val (llmViewModel, appearanceViewModel) =
                rememberSettingsViewModels(navController, container)
            val llmState by llmViewModel.uiState.collectAsStateWithLifecycle()
            val appearanceState by appearanceViewModel.uiState.collectAsStateWithLifecycle()

            SettingsHomeScreen(
                providerName = llmState.provider.gridLabel,
                themeName = stringResource(appearanceState.uiTheme.themeTitleRes),
                expertMode = settings.expertMode,
                onOpenModelService = { navController.navigate(Routes.SETTINGS_MODEL) },
                onOpenGeneration = { navController.navigate(Routes.SETTINGS_GENERATION) },
                onOpenAgent = { navController.navigate(Routes.SETTINGS_AGENT) },
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenSkills = { navController.navigate(Routes.SKILLS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenExpert = { navController.navigate(Routes.SETTINGS_EXPERT) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_MODEL) {
            val (llmViewModel, _) = rememberSettingsViewModels(navController, container)
            val state by llmViewModel.uiState.collectAsStateWithLifecycle()

            LlmServicePage(
                state = state,
                actions = llmViewModel.toActions(),
                onBack = { navController.popBackStack() },
                snackbarHostState = rememberAppSnackbarHostState(),
            )
        }

        composable(Routes.SETTINGS_GENERATION) {
            val (llmViewModel, _) = rememberSettingsViewModels(navController, container)
            val state by llmViewModel.uiState.collectAsStateWithLifecycle()

            GenerationPage(
                state = state,
                actions = llmViewModel.toActions(),
                onBack = { navController.popBackStack() },
                snackbarHostState = rememberAppSnackbarHostState(),
            )
        }

        composable(Routes.SETTINGS_AGENT) {
            val (llmViewModel, _) = rememberSettingsViewModels(navController, container)
            val state by llmViewModel.uiState.collectAsStateWithLifecycle()

            AgentControlPage(
                state = state,
                actions = llmViewModel.toActions(),
                onBack = { navController.popBackStack() },
                snackbarHostState = rememberAppSnackbarHostState(),
            )
        }

        composable(Routes.SETTINGS_EXPERT) {
            val (llmViewModel, _) = rememberSettingsViewModels(navController, container)
            val state by llmViewModel.uiState.collectAsStateWithLifecycle()

            com.paw.agent.ui.settings.ExpertSettingsPage(
                state = state,
                actions = llmViewModel.toActions(),
                onBack = { navController.popBackStack() },
                snackbarHostState = rememberAppSnackbarHostState(),
            )
        }

        composable(Routes.ABOUT) {
            val (llmViewModel, _) = rememberSettingsViewModels(navController, container)
            val state by llmViewModel.uiState.collectAsStateWithLifecycle()

            com.paw.agent.ui.about.AboutScreen(
                expertMode = state.expertMode,
                onToggleExpertMode = llmViewModel::onToggleExpertMode,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SKILLS) {
            com.paw.agent.ui.skills.CustomSkillsScreen(
                container = container,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HISTORY) {
            com.paw.agent.ui.history.HistoryScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenDetail = { id -> navController.navigate("history/detail/$id") },
                onOpenCompare = { id1, id2 -> navController.navigate("history/compare/$id1/$id2") },
                onRestoreToChat = {
                    navController.popBackStack(Routes.CHAT, false)
                },
            )
        }

        composable(Routes.HISTORY_DETAIL) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            com.paw.agent.ui.history.HistoryDetailScreen(
                conversationId = id,
                container = container,
                onBack = { navController.popBackStack() },
                onRestoreToChat = {
                    navController.popBackStack(Routes.CHAT, false)
                },
            )
        }

        composable(Routes.HISTORY_COMPARE) { backStackEntry ->
            val id1 = backStackEntry.arguments?.getString("id1").orEmpty()
            val id2 = backStackEntry.arguments?.getString("id2").orEmpty()
            com.paw.agent.ui.history.TaskCompareScreen(
                id1 = id1,
                id2 = id2,
                container = container,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_APPEARANCE) {
            val (_, appearanceViewModel) = rememberSettingsViewModels(navController, container)
            val appearanceState by appearanceViewModel.uiState.collectAsStateWithLifecycle()

            AppearanceSettingsPage(
                state = appearanceState,
                actions = AppearanceActions(
                    onUiThemeChange = appearanceViewModel::onUiThemeChange,
                    onDarkThemeChange = appearanceViewModel::onDarkThemeChange,
                    onDynamicColorChange = appearanceViewModel::onDynamicColorChange,
                ),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Both settings ViewModels are scoped to the navigation graph root rather than
 * to a single destination.
 *
 * That is what makes the two-level structure safe: the LLM form is a draft, so
 * if each page owned its own ViewModel instance, walking from "model service" to
 * "generation" and back would silently discard whatever the user had typed. One
 * shared instance keeps a draft alive across the whole settings flow.
 */
@Composable
private fun rememberSettingsViewModels(
    navController: NavHostController,
    container: AppContainer,
): Pair<LlmSettingsViewModel, AppearanceSettingsViewModel> {
    val owner: ViewModelStoreOwner = remember(navController) {
        navController.getBackStackEntry(navController.graph.id)
    }

    val llmViewModel: LlmSettingsViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = LlmSettingsViewModel.Factory(
            settingsRepository = container.settingsRepository,
            llmClient = container.llmClient,
        ),
    )
    val appearanceViewModel: AppearanceSettingsViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = AppearanceSettingsViewModel.Factory(
            settingsRepository = container.settingsRepository,
        ),
    )

    return llmViewModel to appearanceViewModel
}

/** Binds an [LlmSettingsViewModel] onto the settings pages' action bundle. */
private fun LlmSettingsViewModel.toActions(): LlmSettingsActions = LlmSettingsActions(
    onProviderChange = ::onProviderChange,
    onBaseUrlChange = ::onBaseUrlChange,
    onApiKeyChange = ::onApiKeyChange,
    onModelChange = ::onModelChange,
    onTemperatureChange = ::onTemperatureChange,
    onTopPChange = ::onTopPChange,
    onMaxTokensChange = ::onMaxTokensChange,
    onMaxToolRoundsChange = ::onMaxToolRoundsChange,
    onVisionResolutionModeChange = ::onVisionResolutionModeChange,
    onStreamChange = ::onStreamChange,
    onSystemPromptChange = ::onSystemPromptChange,
    onToggleApiKeyVisibility = ::toggleApiKeyVisibility,
    onSave = ::save,
    onTestConnection = ::testConnection,
    onReset = ::resetToDefaults,
    onToggleExpertMode = ::onToggleExpertMode,
    onToggleSplitVisionLanguage = ::onToggleSplitVisionLanguage,
    onToggleRootMode = ::onToggleRootMode,
    onToggleAdaptivePacing = ::onToggleAdaptivePacing,
)
