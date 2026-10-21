package com.paw.agent.data.settings

import androidx.compose.runtime.Immutable
import com.paw.agent.core.llm.LlmConfig
import kotlinx.coroutines.flow.Flow

/** 高危开关。每一项都会显著削弱默认安全边界，因此在 UI 与仓储层双重约束。 */
enum class HighRiskToggle {
    /** 允许对任意地址使用明文 HTTP（内部仍区分私网/公网）。 */
    ALLOW_HTTP,

    /** 允许对公网地址使用明文 HTTP。依赖 [ALLOW_HTTP]。 */
    ALLOW_PUBLIC_HTTP,

    /** 允许技能执行 Root shell 命令。依赖 ROOT 执行模式。 */
    ALLOW_SHELL_SKILLS,

    /** 允许在敏感（支付/密码）页面继续执行技能与截图。 */
    ALLOW_SENSITIVE_SCREEN,
}

/** 高危开关的变更结果：拒绝时必须给出可展示的原因，而不是静默失败。 */
@Immutable
data class HighRiskResult(
    val applied: Boolean,
    val message: String? = null,
) {
    companion object {
        fun ok(): HighRiskResult = HighRiskResult(true)
        fun rejected(message: String): HighRiskResult = HighRiskResult(false, message)
    }
}

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

    // ---- 资深设置：高危功能 ----
    /** 允许明文 HTTP 传输（默认关闭；关闭时仅 HTTPS）。 */
    val allowHttpTransport: Boolean = false,
    /** 允许公网明文 HTTP（需先开启 [allowHttpTransport]）。 */
    val allowPublicHttp: Boolean = false,
    /** 允许技能执行 Root shell 命令（需 ROOT 执行模式）。 */
    val allowShellSkills: Boolean = false,
    /** 允许在敏感页面继续执行/截图。 */
    val allowSensitiveScreen: Boolean = false,
    /** 「临时跳过风险确认」的截止时间戳（毫秒），0 表示未开启。 */
    val riskBypassUntil: Long = 0L,
) {

    /** 当前技能执行时统一使用的策略快照。 */
    fun skillRuntimePolicy(now: Long = System.currentTimeMillis()) = com.paw.agent.core.skill.SkillRunOptions(
        allowShellSteps = allowShellSkills && rootModeEnabled,
        bypassRiskConfirmation = riskBypassUntil > now,
        allowSensitiveScreen = allowSensitiveScreen,
    )

    fun networkPolicy(): com.paw.agent.core.llm.NetworkPolicy = com.paw.agent.core.llm.NetworkPolicy(
        allowHttp = allowHttpTransport,
        allowPublicHttp = allowPublicHttp,
    )

    /** 是否处于「风险确认临时豁免」窗口内。 */
    fun isRiskBypassActive(now: Long = System.currentTimeMillis()): Boolean = riskBypassUntil > now

    companion object {
        val Default = AppSettings()

        /** 临时豁免的最长时长，避免用户忘记关闭。 */
        const val MAX_RISK_BYPASS_MINUTES = 30
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

    /**
     * 变更高危开关。
     *
     * 依赖关系在这里强制成立（而不是只写在 UI 上）：
     * - 关闭 [HighRiskToggle.ALLOW_HTTP] 会连带关闭 [HighRiskToggle.ALLOW_PUBLIC_HTTP]；
     * - 开启 ALLOW_PUBLIC_HTTP 前必须已开启 ALLOW_HTTP；
     * - 开启 ALLOW_SHELL_SKILLS 前必须已开启 ROOT 执行模式。
     * 拒绝时返回带原因的 [HighRiskResult]，由界面如实展示。
     */
    suspend fun setHighRiskToggle(toggle: HighRiskToggle, enabled: Boolean): HighRiskResult

    /**
     * 开启「临时跳过风险确认」。
     * @param minutes 有效期（1..[AppSettings.MAX_RISK_BYPASS_MINUTES]），0 表示立即关闭。
     */
    suspend fun setRiskBypassMinutes(minutes: Int)
}
