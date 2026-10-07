package com.paw.agent.core.agent

import com.paw.agent.core.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 智能操作节奏与步间自适应间隔识别引擎。
 *
 * 在移动端自动化执行中，固定死板的 delay 往往导致两种极端：
 * 延时过长导致任务缓慢且体验差；延时过短则界面动画尚未走完、软键盘尚未弹起或网络加载未完成，
 * 从而造成漏击和执行失败。
 *
 * 本引擎综合分析：
 * 1. 当前所执行工具类型（LaunchApp、Swipe、Tap、InputText 等）；
 * 2. 意图特征（如点击的是"确定/搜索/登录"等路由性元素，还是普通列表项）；
 * 3. 语言模型上下文中的等待建议与操作意图；
 * 4. 用户设置的灵敏度节奏偏好；
 *
 * 从而为每一步操作精准匹配最适等待时间。
 */
object AdaptivePacingEngine {

    enum class PacingProfile {
        FAST,      // 极速模式（激进）
        BALANCED,  // 平衡模式（默认智能识别）
        CAREFUL,   // 稳健模式（适合老旧设备或弱网）
    }

    data class PacingDecision(
        val delayMillis: Long,
        val reason: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 识别并计算该步骤工具执行后，应自适应保留的稳定等待时长
     *
     * @param toolCall 模型发起的工具调用
     * @param assistantText 上一步模型给出的思考/规划语言上下文
     * @param profile 节奏偏好
     */
    fun evaluateDelay(
        toolCall: ToolCall,
        assistantText: String = "",
        profile: PacingProfile = PacingProfile.BALANCED,
    ): PacingDecision {
        val toolName = toolCall.name.lowercase()
        val args = toolCall.arguments

        var baseDelay: Long = 400L
        var reason = "常规操作缓冲"

        when {
            toolName.contains("launch_app") || toolName.contains("launch") -> {
                baseDelay = 1800L
                reason = "应用启动与界面加载渲染"
            }

            toolName.contains("deep_link") -> {
                baseDelay = 1400L
                reason = "跨进程路由跳转过渡"
            }

            toolName.contains("swipe") || toolName.contains("scroll") -> {
                baseDelay = 750L
                reason = "滑动惯性平息与列表项加载"
            }

            toolName.contains("input") || toolName.contains("type") -> {
                baseDelay = 450L
                reason = "软键盘输入与文本框焦点响应"
            }

            toolName.contains("key") || toolName.contains("back") || toolName.contains("home") -> {
                baseDelay = 600L
                reason = "系统按键与窗口过渡动画"
            }

            toolName.contains("tap") || toolName.contains("click") -> {
                // 检查点击目标是否可能引起页面路由或网络加载
                val isNavigational = checkNavigationalClick(args)
                if (isNavigational) {
                    baseDelay = 900L
                    reason = "跳转/提交类点击与异步刷新"
                } else {
                    baseDelay = 400L
                    reason = "轻触反馈与组件状态更新"
                }
            }

            toolName.contains("wait") -> {
                // 如果是显式等待工具，读取显式参数
                val explicitSec = runCatching {
                    json.parseToJsonElement(args).jsonObject["seconds"]?.jsonPrimitive?.content?.toDoubleOrNull()
                }.getOrNull()
                if (explicitSec != null && explicitSec > 0) {
                    return PacingDecision((explicitSec * 1000).toLong(), "AI 显式指示等待")
                }
                baseDelay = 1000L
                reason = "任务指令暂停与观察"
            }

            toolName.startsWith("skill_") -> {
                baseDelay = 650L
                reason = "自定义技能复合动作稳定"
            }

            else -> {
                baseDelay = 400L
                reason = "工具执行反馈"
            }
        }

        // 结合模型语言上下文中的提示（如模型提到“等待加载”、“正在缓冲”、“网络较慢”等）
        val contextLower = assistantText.lowercase()
        if (contextLower.contains("等待") || contextLower.contains("加载") || contextLower.contains("loading") || contextLower.contains("缓冲")) {
            baseDelay = (baseDelay * 1.35).toLong()
            reason += " (上下文感知加载中)"
        }

        // 应用节奏 Profile 缩放系数
        val finalDelay = when (profile) {
            PacingProfile.FAST -> (baseDelay * 0.65).toLong().coerceAtLeast(150L)
            PacingProfile.BALANCED -> baseDelay
            PacingProfile.CAREFUL -> (baseDelay * 1.45).toLong()
        }

        return PacingDecision(finalDelay, "$reason (${finalDelay}ms)")
    }

    private fun checkNavigationalClick(argumentsJson: String): Boolean {
        val triggerKeywords = listOf("确定", "搜索", "确认", "登录", "提交", "支付", "下一步", "完成", "search", "submit", "login", "confirm", "next", "enter", "send")
        val values = runCatching {
            val element = json.parseToJsonElement(argumentsJson)
            extractStringValues(element)
        }.getOrNull()

        if (values != null && values.isNotEmpty()) {
            return values.any { text ->
                val lower = text.lowercase()
                triggerKeywords.any { lower.contains(it) }
            }
        }
        val lower = argumentsJson.lowercase()
        return triggerKeywords.any { lower.contains(it) }
    }

    private fun extractStringValues(element: kotlinx.serialization.json.JsonElement): List<String> {
        val result = mutableListOf<String>()
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                element.values.forEach { result.addAll(extractStringValues(it)) }
            }
            is kotlinx.serialization.json.JsonArray -> {
                element.forEach { result.addAll(extractStringValues(it)) }
            }
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (element.isString) result.add(element.content)
            }
        }
        return result
    }
}
