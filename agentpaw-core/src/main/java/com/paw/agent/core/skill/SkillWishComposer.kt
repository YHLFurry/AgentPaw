package com.paw.agent.core.skill

import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.llm.dto.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** 许愿生成的结果。失败也把原始输出带回去，方便用户排查模型到底说了什么。 */
sealed interface SkillWishResult {
    data class Success(
        val definition: SkillDefinition,
        val issues: List<SkillIssue>,
        val rawText: String,
        val repaired: Boolean,
        val attempts: Int,
    ) : SkillWishResult

    data class Failure(
        val message: String,
        val rawText: String = "",
    ) : SkillWishResult
}

/**
 * 「许愿式」技能生成器：把一句自然语言变成一条可执行、可校验的技能。
 *
 * 流程刻意做成多道保险，而不是「让模型直接吐 JSON 然后祈祷」：
 * 1. 提示词里给出**完整的动作目录与字段约束**，并要求只输出 JSON；
 * 2. 解析前先做 JSON 结构修复（大小写、数字字符串、多余包裹）；
 * 3. 解析后跑与编辑器完全相同的 [SkillValidator]；
 * 4. 有错误就带着诊断再问模型一次，只修不重写；
 * 5. 仍然不是合法 JSON 时，退一步按速记格式解析，尽量不让用户白等。
 */
class SkillWishComposer(
    private val llmClient: LlmClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    },
) {

    suspend fun compose(
        wish: String,
        config: LlmConfig,
        existingKeys: Set<String> = emptySet(),
        maxRepairRounds: Int = 1,
    ): SkillWishResult {
        val intent = wish.trim()
        if (intent.isEmpty()) return SkillWishResult.Failure("请先描述你希望这个技能做什么")
        if (!config.isUsable) {
            return SkillWishResult.Failure("模型服务尚未配置完成，请先在设置里填写 Base URL、模型名与 API Key")
        }

        var raw = callModel(config, systemPrompt(), userPrompt(intent))
        if (raw.isBlank()) return SkillWishResult.Failure("模型没有返回内容，请稍后重试")

        var parsed = parse(raw)
        var attempts = 1
        var repaired = false

        // 第一次解析失败 / 校验不过，带着诊断让模型只修必要部分。
        var rounds = 0
        while (rounds < maxRepairRounds) {
            // 用最终会决定「能不能保存」的同一套校验来判断是否需要修正，
            // 而不是只看解析阶段的诊断——解析成功但语义错误（例如 steps 为空）
            // 同样要修。
            val validationIssues = parsed.definition
                ?.let { SkillValidator.validate(it).filter { issue -> issue.isError } }
                .orEmpty()
            val needsRepair = parsed.definition == null || validationIssues.isNotEmpty()
            if (!needsRepair) break

            val diagnostics = buildString {
                validationIssues.take(8).forEach { appendLine("- ${it.message}") }
                if (parsed.definition == null) appendLine("- 输出不是合法的技能 JSON：${parsed.parseError}")
            }
            raw = callModel(config, systemPrompt(), repairPrompt(intent, raw, diagnostics))
            parsed = parse(raw)
            attempts++
            repaired = true
            rounds++
        }

        val definition = parsed.definition
            ?: return SkillWishResult.Failure(
                "没能从模型输出里解析出技能定义：${parsed.parseError ?: "格式不符"}",
                raw,
            )

        val deduped = ensureUniqueKey(definition, existingKeys)
        val normalized = SkillValidator.normalize(
            deduped.copy(source = SkillSource.WISH, updatedAt = System.currentTimeMillis()),
        )
        val issues = SkillValidator.validate(normalized)

        return SkillWishResult.Success(
            definition = normalized,
            issues = issues,
            rawText = raw,
            repaired = repaired,
            attempts = attempts,
        )
    }

    // ------------------------------------------------------------ 模型调用

    private suspend fun callModel(config: LlmConfig, system: String, user: String): String {
        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = user),
            ),
            // 结构化输出要稳定：温度压低，并关闭流式以便一次性拿到完整 JSON。
            temperature = 0.2f,
            maxTokens = 2_048,
            stream = false,
        )
        val builder = StringBuilder()
        llmClient.complete(config.copy(stream = false), request).collect { chunk ->
            if (chunk is LlmChunk.Delta) builder.append(chunk.text)
        }
        return builder.toString().trim()
    }

    private fun systemPrompt(): String = """
        你是 AgentPaw 的「技能编译器」。用户用自然语言描述一个手机自动化需求，你要把它编译成一条可执行的技能 JSON。

        只输出 JSON，不要解释、不要 Markdown 代码块。

        【输出结构】
        {
          "displayName": "简短中文名（<= 20 字）",
          "key": "skill_ 开头的英文小写标识",
          "description": "什么时候用、做什么（40~200 字，写给另一个 AI 看）",
          "emoji": "一个表情符号",
          "category": "分类，如 社交/效率/系统/购物",
          "parameters": [
            {"key":"keyword","label":"关键词","type":"TEXT|NUMBER|BOOLEAN|CHOICE","description":"说明","required":true,"defaultValue":"","choices":[],"example":"示例"}
          ],
          "steps": [
            {"id":"step_1","kind":"动作类型","label":"这一步做什么","target":"主参数","x":500,"y":500,"toX":null,"toY":null,
             "durationMs":350,"delayMs":400,"timeoutMs":4000,"maxAttempts":4,
             "exactMatch":false,"tapAfter":true,"clearFirst":false,"condition":"","onError":"ABORT|CONTINUE|RETRY","storeAs":""}
          ],
          "variables": {},
          "guard": {"confirmBeforeRun": false, "allowedPackages": [], "maxDurationMs": 120000, "allowSensitiveScreen": false, "allowShell": false}
        }

        【可用动作】（kind 必须严格使用下列大写值）
        ${SkillCatalog.llmCatalog()}

        【硬性规则】
        1. 步骤 1~15 个，能少不多；每一步都要能被真机执行，不要写「识别屏幕上的按钮」这种无法映射的动作。
        2. 需要用户输入的内容必须抽象成 parameters，并在步骤里用 {{参数名}} 引用；禁止把真实姓名、手机号、验证码写死。
        3. 不要使用 SHELL，除非用户明确要求执行系统命令；即使使用，也要把 guard.allowShell 设为 true 并在 label 里说明后果。
        4. 不要包含支付、转账、输入密码的步骤；如果用户要求，改为在 description 里说明「需用户手动完成支付」。
        5. 每个 step 都要有唯一的 id（step_1、step_2…）和可读的 label。
        6. key 只能包含小写字母、数字、下划线，且以 skill_ 开头。
        7. 打开应用用 LAUNCH_APP（target 写应用名），找按钮用 TAP_ELEMENT（target 写按钮文字），
           在长列表里找东西用 SCROLL_UNTIL_FOUND，等待加载用 WAIT 或 WAIT_FOR_TEXT。
        8. 不确定坐标时不要用 TAP_POINT，用 TAP_ELEMENT。
    """.trimIndent()

    private fun userPrompt(intent: String): String = "用户想要这样的技能：\n$intent\n\n请输出技能 JSON。"

    private fun repairPrompt(intent: String, previous: String, diagnostics: String): String = """
        你上一次输出的技能 JSON 存在问题，请修复后重新输出**完整**的 JSON（仍然只输出 JSON）。

        原始需求：
        $intent

        上一次输出：
        ${previous.take(6_000)}

        需要修复的问题：
        $diagnostics
    """.trimIndent()

    // -------------------------------------------------------------- 解析

    internal data class Parsed(
        val definition: SkillDefinition?,
        val issues: List<SkillIssue>,
        val parseError: String? = null,
    )

    internal fun parse(raw: String): Parsed {
        val candidate = extractJson(raw)

        // 先尝试 JSON；失败后把速记格式当作兜底（有些模型会"贴心地"写成清单）。
        if (candidate != null) {
            val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            val obj = element as? JsonObject
            if (obj != null) {
                val decoded = runCatching {
                    json.decodeFromJsonElement(SkillDefinition.serializer(), JsonDoctor.repair(obj))
                }.getOrNull()
                if (decoded != null) {
                    return Parsed(decoded, emptyList())
                }
            }
        }

        val dsl = SkillDsl.parse(raw)
        if (dsl.definition != null && dsl.stepCount > 0) {
            return Parsed(dsl.definition, dsl.issues)
        }

        return Parsed(
            definition = null,
            issues = emptyList(),
            parseError = if (candidate == null) "没有找到 JSON 对象" else "JSON 字段不合法",
        )
    }

    /** 从可能包裹了说明文字 / 代码块的输出里取出 JSON 主体。 */
    internal fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        val withoutFence = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = withoutFence.indexOf('{')
        if (start < 0) return null
        val end = withoutFence.lastIndexOf('}')
        if (end <= start) return null
        return withoutFence.substring(start, end + 1)
    }

    private fun ensureUniqueKey(def: SkillDefinition, existingKeys: Set<String>): SkillDefinition {
        if (def.key !in existingKeys) return def
        var suffix = 2
        var candidate = "${def.key}_$suffix"
        while (candidate in existingKeys) {
            suffix++
            candidate = "${def.key}_$suffix"
        }
        return def.copy(key = candidate)
    }
}

/**
 * JSON 修复器。
 *
 * 模型最常见的三种偏差都能在这里被吸收，不必浪费一次请求：
 * 1. 枚举大小写不一致（`tap_element` / `Tap_Element`）；
 * 2. 数字被写成字符串（`"x": "500"`）；
 * 3. 布尔被写成 `"true"` / `"是"`。
 */
internal object JsonDoctor {

    private val INT_FIELDS = listOf("x", "y", "toX", "toY", "maxAttempts")
    private val LONG_FIELDS = listOf("durationMs", "delayMs", "timeoutMs")
    private val BOOL_FIELDS = listOf("exactMatch", "tapAfter", "clearFirst", "enabled", "required")

    fun repair(root: JsonObject): JsonObject {
        val result = root.toMutableMap()

        (result["steps"] as? JsonArray)?.let { steps ->
            result["steps"] = JsonArray(
                steps.map { element ->
                    val step = (element as? JsonObject) ?: return@map element
                    val fields = step.toMutableMap()
                    fields["kind"]?.let { fields["kind"] = upper(it) }
                    fields["onError"]?.let { fields["onError"] = upper(it) }
                    INT_FIELDS.forEach { key -> fields[key]?.let { fields[key] = asInt(it) } }
                    LONG_FIELDS.forEach { key -> fields[key]?.let { fields[key] = asLong(it) } }
                    BOOL_FIELDS.forEach { key -> fields[key]?.let { fields[key] = asBool(it) } }
                    JsonObject(fields)
                },
            )
        }

        (result["parameters"] as? JsonArray)?.let { params ->
            result["parameters"] = JsonArray(
                params.map { element ->
                    val param = (element as? JsonObject) ?: return@map element
                    val fields = param.toMutableMap()
                    fields["type"]?.let { fields["type"] = upper(it) }
                    fields["required"]?.let { fields["required"] = asBool(it) }
                    JsonObject(fields)
                },
            )
        }

        (result["guard"] as? JsonObject)?.let { guard ->
            val fields = guard.toMutableMap()
            listOf("confirmBeforeRun", "allowSensitiveScreen", "allowShell").forEach { key ->
                fields[key]?.let { fields[key] = asBool(it) }
            }
            fields["maxDurationMs"]?.let { fields["maxDurationMs"] = asLong(it) }
            result["guard"] = JsonObject(fields)
        }

        if (result["enabled"] != null) result["enabled"] = asBool(result.getValue("enabled"))

        return JsonObject(result)
    }

    private fun upper(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return element
        return JsonPrimitive(primitive.content.trim().uppercase())
    }

    private fun asInt(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return element
        val value = primitive.content.trim().toDoubleOrNull() ?: return primitive
        return JsonPrimitive(value.toInt())
    }

    private fun asLong(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return element
        val value = primitive.content.trim().toLongOrNull() ?: return primitive
        return JsonPrimitive(value)
    }

    private fun asBool(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return element
        primitive.booleanOrNull?.let { return JsonPrimitive(it) }
        return when (primitive.content.trim().lowercase()) {
            "true", "是", "on", "1", "yes" -> JsonPrimitive(true)
            "false", "否", "off", "0", "no", "" -> JsonPrimitive(false)
            else -> primitive
        }
    }
}
