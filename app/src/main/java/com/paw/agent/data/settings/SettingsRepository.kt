package com.paw.agent.data.settings

import androidx.compose.runtime.Immutable
import com.paw.agent.core.llm.LlmConfig
import kotlinx.coroutines.flow.Flow

/** User-facing app preferences, persisted with DataStore. */
@Immutable
data class AppSettings(
    val llm: LlmConfig = LlmConfig(),
    val dynamicColor: Boolean = true,
    val darkTheme: Boolean = false,
    /** Which design system renders the UI; see [UiThemeMode]. */
    val uiTheme: UiThemeMode = UiThemeMode.Default,
    /** 资深（专家）模式开关，默认隐藏，在"关于"页面长按大图标解锁后置为 true */
    val expertMode: Boolean = false,
    /** 视觉与语言分离显示模式（专家模式解锁后可用） */
    val splitVisionLanguageMode: Boolean = false,
    /** ROOT 执行优先模式 */
    val rootModeEnabled: Boolean = false,
    /** AI 智能识别步间等待时间与自适应节奏 */
    val adaptivePacingEnabled: Boolean = true,
    /** Keystore 解密失败标志，用于提示用户重新输入凭据 */
    val apiKeyDecryptionFailed: Boolean = false,
) {
    companion object {
        val Default = AppSettings()
    }
}

/** Reads and writes [AppSettings]. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun updateLlm(transform: (LlmConfig) -> LlmConfig)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun setUiTheme(mode: UiThemeMode)
    suspend fun setExpertMode(enabled: Boolean)
    suspend fun setSplitVisionLanguageMode(enabled: Boolean)
    suspend fun setRootModeEnabled(enabled: Boolean)
    suspend fun setAdaptivePacingEnabled(enabled: Boolean)
    /** Clears the LLM section only, so appearance choices survive an LLM reset. */
    suspend fun resetLlm()
}
