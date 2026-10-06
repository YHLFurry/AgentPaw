package com.paw.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.data.settings.UiThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Appearance preferences as shown on screen. */
data class AppearanceUiState(
    val uiTheme: UiThemeMode = UiThemeMode.Default,
    val darkTheme: Boolean = false,
    val dynamicColor: Boolean = true,
)

/** Callbacks owned by the appearance section; it never touches LLM state. */
data class AppearanceActions(
    val onUiThemeChange: (UiThemeMode) -> Unit,
    val onDarkThemeChange: (Boolean) -> Unit,
    val onDynamicColorChange: (Boolean) -> Unit,
)

/**
 * Appearance settings.
 *
 * Every change is written straight through to the repository: the theme must
 * flip on the same frame the user taps, so there is no draft state and no
 * "save" button here — unlike the LLM section, which edits a draft.
 */
class AppearanceSettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<AppearanceUiState> = settingsRepository.settings
        .map { settings ->
            AppearanceUiState(
                uiTheme = settings.uiTheme,
                darkTheme = settings.darkTheme,
                dynamicColor = settings.dynamicColor,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppearanceUiState(),
        )

    fun onUiThemeChange(mode: UiThemeMode) {
        viewModelScope.launch { settingsRepository.setUiTheme(mode) }
    }

    fun onDarkThemeChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(enabled) }
    }

    fun onDynamicColorChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppearanceSettingsViewModel(settingsRepository) as T
    }
}
