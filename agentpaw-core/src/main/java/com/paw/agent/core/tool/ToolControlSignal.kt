package com.paw.agent.core.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 结构化工具控制信号。
 * 统一调度敏感页面安全暂停与高风险操作二次授权确认，
 * 防止模型在敏感状态下擅自继续推进操作。
 */
@Serializable
sealed class ToolControlSignal {

    @Serializable
    object None : ToolControlSignal()

    /**
     * 敏感页面安全暂停（密码、支付、CVV、短信验证码等）
     */
    @Serializable
    data class SafetyPause(
        val reason: String = "检测到敏感密码/支付页面，已自动暂停操作以确保安全",
        val message: String = "",
    ) : ToolControlSignal()

    /**
     * 高风险操作拦截，等待用户显式确认授权
     */
    @Serializable
    data class RequiresConfirmation(
        val action: String,
        val target: String,
        val reason: String,
        val riskLevel: String = "HIGH",
        val toolName: String = "",
        val arguments: String = "",
    ) : ToolControlSignal()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(
            toolResultContent: String,
            toolName: String = "",
            arguments: String = "",
        ): ToolControlSignal {
            if (toolResultContent.isBlank()) return None
            return runCatching {
                val element = json.parseToJsonElement(toolResultContent)
                val obj = element.jsonObject
                val status = obj["status"]?.jsonPrimitive?.content ?: ""
                val isSafetyPause = obj["is_safety_pause"]?.jsonPrimitive?.booleanOrNull == true ||
                    toolResultContent.contains("[SAFETY PAUSE]", ignoreCase = true)

                when {
                    status == "paused" || isSafetyPause -> {
                        val message = obj["message"]?.jsonPrimitive?.content
                            ?: "检测到敏感密码/支付页面，自动化操作已安全暂停。请在手机设备上手动完成该敏感步骤。"
                        SafetyPause(
                            reason = message,
                            message = message,
                        )
                    }

                    status == "requires_confirmation" ||
                        obj["requires_confirmation"]?.jsonPrimitive?.booleanOrNull == true ||
                        toolResultContent.contains("\"requires_confirmation\"") -> {
                        val action = obj["action"]?.jsonPrimitive?.content ?: toolName
                        val target = obj["target"]?.jsonPrimitive?.content ?: "敏感操作目标"
                        val reason = obj["reason"]?.jsonPrimitive?.content ?: "命中高风险防护规则，需用户确认授权"
                        val riskLevel = obj["risk_level"]?.jsonPrimitive?.content ?: "HIGH"
                        val actualToolName = obj["tool_name"]?.jsonPrimitive?.content?.ifBlank { null }
                            ?: toolName
                        val actualArgs = obj["arguments"]?.jsonPrimitive?.content?.ifBlank { null }
                            ?: arguments
                        RequiresConfirmation(
                            action = action,
                            target = target,
                            reason = reason,
                            riskLevel = riskLevel,
                            toolName = actualToolName,
                            arguments = actualArgs,
                        )
                    }

                    else -> None
                }
            }.getOrDefault(None)
        }
    }
}
