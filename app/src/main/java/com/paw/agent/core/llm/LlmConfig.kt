package com.paw.agent.core.llm

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

    QWEN(
        displayName = "Qwen / DashScope",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-vl-max",
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
data class LlmConfig(
    val provider: LlmProvider = LlmProvider.CUSTOM,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 2048,
    val maxToolRounds: Int = 15,
    val visionResolutionMode: String = "AUTO",
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
            "You are AgentPaw, an autonomous AI assistant operating an Android mobile device.\n" +
            "You can interact with apps, read screen content, and perform multi-step workflows using available Tools and Skills.\n\n" +
            "Core Guidelines:\n" +
            "1. Coordinate System: All screen coordinates (x, y) are normalized integers in [0, 1000]. (0, 0) is top-left, (1000, 1000) is bottom-right.\n" +
            "2. Vision & Screen State: Call `take_screenshot` (mode='AUTO' for balanced tokens, 'FAST' for high-speed triage, or 'HIGH'/crop_roi for fine details) or `get_screen_state` to observe the interface before acting.\n" +
            "3. Skills First for Efficiency:\n" +
            "   - Use `skill_scroll_and_find` to locate items in long lists without repeated screenshots.\n" +
            "   - Use `skill_open_and_search` for direct app search workflows.\n" +
            "   - Use `skill_return_home` when resetting or switching contexts.\n" +
            "4. Interaction Tools: Use `tap`, `double_tap`, `long_press`, `swipe`, `input_text`, and `key_action` ('BACK', 'HOME', 'RECENTS', 'ENTER'). Use `wait_seconds` if an app is loading.\n" +
            "5. Safety Red Line: NEVER type payment passwords, PINs, or confirm payments. Pause and ask the user to complete sensitive credentials.\n" +
            "6. Concise Feedback: Briefly explain what you are doing on each step and provide a clear confirmation upon task completion."
    }
}
