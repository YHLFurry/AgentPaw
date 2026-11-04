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
    val suggestedModels: List<String> = emptyList(),
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true,
        suggestedModels = listOf("gpt-4o", "gpt-4o-mini", "o3-mini", "gpt-4-turbo"),
    ),

    QWEN(
        displayName = "Qwen / DashScope",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-vl-max",
        requiresApiKey = true,
        suggestedModels = listOf("qwen-vl-max", "qwen-vl-plus", "qwen-plus", "qwen-turbo", "qwen-max"),
    ),

    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        requiresApiKey = true,
        suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
    ),

    GEMINI(
        displayName = "Google Gemini (OpenAI compat)",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.0-flash",
        requiresApiKey = true,
        suggestedModels = listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"),
    ),

    MOONSHOT(
        displayName = "Moonshot / Kimi",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
        requiresApiKey = true,
        suggestedModels = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
    ),

    OLLAMA(
        displayName = "Ollama (local)",
        defaultBaseUrl = "http://10.0.2.2:11434/v1",
        defaultModel = "qwen2.5:7b",
        requiresApiKey = false,
        apiKeyHeader = "Authorization",
        apiKeyPrefix = "",
        suggestedModels = listOf("qwen2.5:7b", "llama3.2-vision", "deepseek-r1:7b", "llava:7b"),
    ),

    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        defaultBaseUrl = "",
        defaultModel = "",
        requiresApiKey = false,
        suggestedModels = listOf("deepseek-chat", "qwen-vl-max", "gpt-4o", "claude-3-5-sonnet-20241022"),
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
    /** 0 uses the model's known context limit, or a conservative fallback. */
    val maxTotalTokens: Int = 0,
    val detectedTotalTokens: Int = 0,
    val detectedFor: String = "",
    val reasoningEffort: String = "AUTO",
    val autoCompress: Boolean = true,
    val maxToolRounds: Int = 15,
    val visionResolutionMode: String = "AUTO",
    val pacingProfile: String = "BALANCED",
    val timeoutSeconds: Int = 60,
    val stream: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val tools: List<com.paw.agent.core.model.ToolDefinition> = emptyList(),
) {
    /** True when the config has enough information to issue a request. */
    val isUsable: Boolean
        get() = validationErrors().isEmpty()

    fun validationErrors(): List<String> = buildList {
        val validUrl = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()?.let { uri ->
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.rawQuery == null && (uri.port == -1 || uri.port in 1..65535)
        } == true
        if (!validUrl) add("接口地址必须是完整的 HTTP(S) 地址，且不能包含账号、查询参数或片段")
        if (model.isBlank() || model.any { it.isISOControl() }) add("模型名称不能为空或包含控制字符")
        if (provider.requiresApiKey && apiKey.isBlank()) add("该服务商需要 API Key")
        if (apiKey.any { it.isISOControl() }) add("API Key 不能包含换行或控制字符")
        if (!temperature.isFinite() || temperature !in 0f..2f) add("温度需在 0 到 2 之间")
        if (!topP.isFinite() || topP !in 0f..1f) add("Top P 需在 0 到 1 之间")
        if (maxTokens !in 1..MAX_TOKEN_LIMIT) add("最大输出 Token 需在 1 到 $MAX_TOKEN_LIMIT 之间")
        if (maxTotalTokens !in 0..MAX_TOKEN_LIMIT || effectiveMaxTotalTokens <= maxTokens) {
            add("最大总 Token 必须大于输出预算，且不超过 $MAX_TOKEN_LIMIT")
        }
        if (reasoningEffort !in REASONING_EFFORTS) add("不支持的思考强度")
        if (timeoutSeconds !in 10..300) add("请求超时需在 10 到 300 秒之间")
        if (maxToolRounds < 0) add("最大执行步数需为 0 或正整数")
        if (visionResolutionMode !in setOf("AUTO", "FAST", "HIGH")) add("不支持的截图分辨率模式")
        if (pacingProfile !in setOf("FAST", "BALANCED", "CAREFUL")) add("不支持的执行节奏")
    }

    val isReasoningModel: Boolean
        get() = Regex("^(o[134](?:-|$)|gpt-[5-9](?:[.\\-]|$))", RegexOption.IGNORE_CASE)
            .containsMatchIn(model.substringAfterLast('/'))

    /** Local estimate used for budgeting; compatible providers may advertise different limits. */
    val effectiveMaxTotalTokens: Int
        get() {
            if (maxTotalTokens > 0) return maxTotalTokens
            if (detectedFor == "$baseUrl|$model" && detectedTotalTokens in 1..2_000_000) {
                return detectedTotalTokens
            }
            val id = model.lowercase()
            val suffix = Regex("(?:^|[-_/])(\\d{1,4})([km])(?:[-_/]|$)").find(id)
            suffix?.let {
                val value = it.groupValues[1].toInt() * if (it.groupValues[2] == "m") 1_000_000L else 1_000L
                if (value in 1L..MAX_TOKEN_LIMIT.toLong()) return value.toInt()
            }
            return when {
                id.startsWith("gpt-4o") -> 128_000
                else -> 16_000
            }
        }

    companion object {
        const val MAX_TOKEN_LIMIT = 2_000_000
        val REASONING_EFFORTS = listOf("AUTO", "NONE", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX")
        const val DEFAULT_SYSTEM_PROMPT =
            "You are AgentPaw, an autonomous AI assistant operating an Android mobile device.\n" +
            "You can interact with apps, read screen content, and perform multi-step workflows using available Tools and Skills.\n\n" +
            "Core Guidelines:\n" +
            "1. Coordinate System: All screen coordinates (x, y) are normalized integers in [0, 1000]. (0, 0) is top-left, (1000, 1000) is bottom-right. BUT for clicking a known button, do NOT guess coordinates from the screenshot — use `click_element` instead (see #4).\n" +
            "2. Vision & Screen State: Call `take_screenshot` (mode='AUTO' for balanced tokens, 'FAST' for high-speed triage, or 'HIGH'/crop_roi for fine details) or `get_screen_state` to observe the interface before acting. `get_screen_state` returns each element's real-pixel `bounds` and `center` plus an `index`.\n" +
            "3. Skills First for Efficiency:\n" +
            "   - Use `skill_scroll_and_find` to locate items in long lists without repeated screenshots.\n" +
            "   - Use `skill_open_and_search` for direct app search workflows.\n" +
            "   - Use `skill_return_home` when resetting or switching contexts.\n" +
            "4. Interaction Tools — prefer pixel-accurate clicks:\n" +
            "   - `click_element` (PREFERRED for buttons/controls): click a UI element via the accessibility tree using `bounds` (pixel [left,top,right,bottom] from get_screen_state), `index` (0-based), or `text`/`view_id`. This is pixel-accurate and almost never needs a retry.\n" +
            "   - `tap` / `double_tap` / `long_press` with normalized [0,1000] coords: only for free-form points that are NOT a clean UI element (e.g. a spot on a map/image). If the screenshot was cropped with `crop_roi`, pass the SAME `crop_roi` to the tap tool.\n" +
            "   - `swipe`, `input_text`, `key_action` ('BACK', 'HOME', 'RECENTS', 'ENTER'), and `wait_seconds` (use when an app is still loading/animating).\n" +
            "5. Safety Red Line: NEVER type payment passwords, PINs, or confirm payments. Pause and ask the user to complete sensitive credentials.\n" +
            "6. Concise Feedback: Briefly explain what you are doing on each step and provide a clear confirmation upon task completion."
    }
}
