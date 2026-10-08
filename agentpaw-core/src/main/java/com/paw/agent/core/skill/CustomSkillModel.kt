package com.paw.agent.core.skill

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.device.PhoneController
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自定义技能的动作步骤类型
 */
@Serializable
enum class SkillActionType {
    LAUNCH_APP,       // 启动应用 (target 为应用名或包名)
    TAP_COORDINATE,   // 坐标点击 (x, y 为 0-1000 归一化坐标)
    TAP_ELEMENT,      // 文本元素查找并点击 (target 为匹配文本)
    INPUT_TEXT,       // 输入文本 (target 为文字，支持 {{var}} 模板)
    SWIPE,            // 滑动 (target 包含方向或 extra 坐标)
    PRESS_KEY,        // 系统按键 (target: back / home / recents / enter)
    WAIT,             // 等待时间 (waitMillis)
    SHELL_COMMAND,    // 执行 Root/Shell 脚本 (target 为命令)
}

/**
 * 自定义技能单个动作配置
 */
@Serializable
data class SkillActionStep(
    val type: SkillActionType,
    val target: String = "",
    val x: Int = 500,
    val y: Int = 500,
    val extra: String = "",
    val waitMillis: Long = 500L,
    val exactMatch: Boolean = false,
    val description: String = "",
)

/**
 * 用户自定义技能的完整定义配置，可序列化并保存到磁盘
 */
@Serializable
data class CustomSkillDefinition(
    val id: String,
    val name: String,                  // 工具标识，如 "skill_my_wechat"
    val displayName: String,           // 显示名称，如 "微信给好友发消息"
    val description: String,           // 技能用途描述（供大模型识别调用时机）
    val parametersSchema: String = """{"type":"object","properties":{}}""",
    val actions: List<SkillActionStep> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 运行时可执行的自定义技能实现类，实现 [AgentSkill]，自动被注册为工具供大模型调用
 */
class CustomExecutableSkill(
    val definition: CustomSkillDefinition,
) : AgentSkill {

    override val name: String = definition.name
    override val description: String = definition.description
    override val parametersSchema: String = definition.parametersSchema

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        arguments: String,
        phoneController: PhoneController,
        context: AgentContext,
    ): String {
        val argsMap = runCatching {
            json.parseToJsonElement(arguments).jsonObject.mapValues {
                it.value.jsonPrimitive.contentOrNull ?: ""
            }
        }.getOrDefault(emptyMap())

        var executedCount = 0

        for (step in definition.actions) {
            // 对 step.target 进行模板占位符替换，如 {{keyword}} -> 具体传参
            val interpolatedTarget = interpolate(step.target, argsMap)

            // 安全检查：在可触控操作前核验当前屏幕是否处于敏感支付/密码页面
            if (step.type != SkillActionType.WAIT) {
                val state = phoneController.getScreenState()
                val allText = state.elements.joinToString(" ") { it.text + " " + it.contentDescription }
                if (com.paw.agent.core.tool.android.SafetyGuard.isSensitive(allText)) {
                    return """{"status":"paused","is_safety_pause":true,"reason":"检测到敏感密码/支付页面，自动化技能已安全暂停","message":"[SAFETY PAUSE] Detected sensitive password/payment screen. Automated custom skill is paused for security."}"""
                }
            }

            when (step.type) {
                SkillActionType.LAUNCH_APP -> {
                    phoneController.launchApp(interpolatedTarget)
                }

                SkillActionType.TAP_COORDINATE -> {
                    phoneController.tap(step.x, step.y)
                }

                SkillActionType.TAP_ELEMENT -> {
                    val state = phoneController.getScreenState()
                    val matched = if (step.exactMatch) {
                        state.elements.firstOrNull { elem ->
                            elem.text.equals(interpolatedTarget, ignoreCase = true) ||
                                elem.contentDescription.equals(interpolatedTarget, ignoreCase = true)
                        }
                    } else {
                        // 优先精准全字匹配，其次回退至包含匹配，避免"搜索"误点"搜索结果"
                        state.elements.firstOrNull { elem ->
                            elem.text.equals(interpolatedTarget, ignoreCase = true) ||
                                elem.contentDescription.equals(interpolatedTarget, ignoreCase = true)
                        } ?: state.elements.firstOrNull { elem ->
                            elem.text.contains(interpolatedTarget, ignoreCase = true) ||
                                elem.contentDescription.contains(interpolatedTarget, ignoreCase = true)
                        }
                    }
                    if (matched != null) {
                        phoneController.tapAtPixel(
                            matched.bounds.centerX.toFloat(),
                            matched.bounds.centerY.toFloat(),
                        )
                    }
                }

                SkillActionType.INPUT_TEXT -> {
                    phoneController.inputText(interpolatedTarget, clearBeforeInput = false)
                }

                SkillActionType.SWIPE -> {
                    // 默认下拉/上滑
                    val startY = step.y.coerceIn(100, 900)
                    val endY = if (startY > 500) startY - 400 else startY + 400
                    phoneController.swipe(step.x, startY, step.x, endY, 350)
                }

                SkillActionType.PRESS_KEY -> {
                    when (interpolatedTarget.lowercase()) {
                        "back" -> phoneController.pressBack()
                        "home" -> phoneController.pressHome()
                        "recents" -> phoneController.pressRecents()
                        "enter" -> phoneController.pressEnter()
                        else -> phoneController.pressBack()
                    }
                }

                SkillActionType.WAIT -> {
                    val rawWait = interpolatedTarget.toLongOrNull() ?: step.waitMillis
                    val waitTime = rawWait.coerceIn(0L, 30_000L)
                    if (waitTime > 0) {
                        delay(waitTime)
                    }
                }

                SkillActionType.SHELL_COMMAND -> {
                    val risk = com.paw.agent.core.agent.RiskActionGuard.evaluate("shell_command", interpolatedTarget, "")
                    val isConfirmed = context.bypassSafetyGuard || context.isGranted("risk_confirmed:$name") || context.isGranted("risk_confirmed")
                    if (risk.requiresConfirmation && !isConfirmed) {
                        return com.paw.agent.core.tool.android.SafetyGuard.formatConfirmationPayload(risk, toolName = name, arguments = arguments)
                    }
                    if (phoneController is com.paw.agent.device.HybridPhoneController && phoneController.isRootAvailable) {
                        phoneController.rootController.executeCommand(interpolatedTarget)
                    }
                }
            }

            executedCount++
            if (step.type != SkillActionType.WAIT && step.waitMillis > 0) {
                delay(step.waitMillis.coerceIn(0L, 10_000L))
            }
        }

        val resultObj = kotlinx.serialization.json.buildJsonObject {
            put("status", kotlinx.serialization.json.JsonPrimitive("success"))
            put("skill", kotlinx.serialization.json.JsonPrimitive(name))
            put("executed_steps", kotlinx.serialization.json.JsonPrimitive(executedCount))
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), resultObj)
    }

    private fun interpolate(template: String, args: Map<String, String>): String {
        var result = template
        args.forEach { (k, v) ->
            result = result.replace("{{$k}}", v)
        }
        return result
    }
}
