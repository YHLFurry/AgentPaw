package com.paw.agent.core.llm

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Supported provider presets.
 *
 * [CUSTOM] is the escape hatch: any endpoint that speaks the OpenAI
 * chat-completions protocol works, so new providers do not need code changes.
 */
@Serializable
enum class LlmProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean,
    val apiKeyHeader: String = "Authorization",
    val apiKeyPrefix: String = "Bearer ",
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true,
    ),

    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        requiresApiKey = true,
    ),

    GEMINI(
        displayName = "Google Gemini (OpenAI compat)",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.0-flash",
        requiresApiKey = true,
    ),

    MOONSHOT(
        displayName = "Moonshot / Kimi",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
        requiresApiKey = true,
    ),

    OLLAMA(
        displayName = "Ollama (local)",
        defaultBaseUrl = "http://10.0.2.2:11434/v1",
        defaultModel = "qwen2.5:7b",
        requiresApiKey = false,
        apiKeyHeader = "Authorization",
        apiKeyPrefix = "",
    ),

    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        defaultBaseUrl = "",
        defaultModel = "",
        requiresApiKey = false,
    ),
    ;

    companion object {
        fun fromName(value: String?): LlmProvider =
            entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}

/**
 * Everything needed to talk to a model. Assembled from the user's settings screen
 * and passed to the client on every call.
 */
@Immutable
data class LlmConfig(
    val provider: LlmProvider = LlmProvider.CUSTOM,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 2048,
    val stream: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val tools: List<com.paw.agent.core.model.ToolDefinition> = emptyList(),
) {
    /** True when the config has enough information to issue a request. */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() &&
            (!provider.requiresApiKey || apiKey.isNotBlank())

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are AgentPaw, a helpful assistant running on an Android phone. " +
                "Be concise, accurate, and honest about what you do not know."
    }
}
