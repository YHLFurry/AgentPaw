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

            when (step.type) {
                SkillActionType.LAUNCH_APP -> {
                    phoneController.launchApp(interpolatedTarget)
                }

                SkillActionType.TAP_COORDINATE -> {
                    phoneController.tap(step.x, step.y)
                }

                SkillActionType.TAP_ELEMENT -> {
                    val state = phoneController.getScreenState()
                    val matched = state.elements.firstOrNull { elem ->
                        elem.text.contains(interpolatedTarget, ignoreCase = true) ||
                            elem.contentDescription.contains(interpolatedTarget, ignoreCase = true)
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
                    // 仅等待
                }

                SkillActionType.SHELL_COMMAND -> {
                    // 若是 HybridPhoneController 且支持 Root，可直接执行
                    if (phoneController is com.paw.agent.device.HybridPhoneController && phoneController.isRootAvailable) {
                        phoneController.rootController.executeCommand(interpolatedTarget)
                    }
                }
            }

            executedCount++
            if (step.waitMillis > 0) {
                delay(step.waitMillis)
            }
        }

        return """{"status":"success","skill":"$name","executed_steps":$executedCount}"""
    }

    private fun interpolate(template: String, args: Map<String, String>): String {
        var result = template
        args.forEach { (k, v) ->
            result = result.replace("{{$k}}", v)
        }
        return result
    }
}
