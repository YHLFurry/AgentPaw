package com.paw.agent.core.agent

import kotlinx.serialization.Serializable

enum class RiskLevel {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL,
}

@Serializable
data class RiskDecision(
    val isRisk: Boolean = false,
    val level: RiskLevel = RiskLevel.LOW,
    val category: String = "NORMAL",
    val action: String = "",
    val target: String = "",
    val impact: String = "",
    val requiresConfirmation: Boolean = false,
)

/**
 * 动作安全分级与拦截防护体系 (Risk Action Guard)
 *
 * 针对可能造成资金损失、隐私泄露、数据被删或不可逆影响的高危动作实施分级管控：
 * 1. CRITICAL: 支付、转账、密码/安全凭据录入、钱包调用
 * 2. HIGH: 数据删除、账号注销、系统权限/设备管理器授权、Root提权
 * 3. MODERATE: 发送消息、发布动态、提交表单、批量操作
 */
object RiskActionGuard {

    private val PAYMENT_REGEX = Regex("""(?i)\b(pay|payment|checkout|transfer)\b|支付|付款|转账|买单|立即支付|确认付款|确认支付|去支付|提交订单|立即购买|密码支付|指纹支付""")

    private val DESTRUCTION_REGEX = Regex("""(?i)\b(delete|remove|destroy|wipe|uninstall|format)\b|删除|清空|销毁|永久删除|注销账号|解除绑定|格式化|恢复出厂|卸载""")

    private val AUTHORIZATION_REGEX = Regex("""(?i)\b(grant|authorize|permission|root|superuser)\b|允许|始终允许|授予权限|同意并授权|激活设备管理器|获取root|提权""")

    private val SUBMIT_REGEX = Regex("""(?i)\b(send|submit|post)\b|发送|确定提交|发布|立即发布|发朋友圈|确认发送""")

    private val PAYMENT_SCHEMES = listOf(
        "alipays://", "alipayqr://", "weixin://dl/businessWeb", "upay://", "unionpay://",
    )

    /**
     * 对即将执行的工具与上下文参数进行综合风险评估
     */
    fun evaluate(
        toolName: String,
        arguments: String,
        screenContextText: String = "",
    ): RiskDecision {
        val lowerArgs = arguments.lowercase()
        val lowerScreen = screenContextText.lowercase()

        // 0. Shell 命令执行风险分析 (CRITICAL)
        if (toolName.equals("shell_command", ignoreCase = true) || toolName.equals("run_script", ignoreCase = true)) {
            return RiskDecision(
                isRisk = true,
                level = RiskLevel.CRITICAL,
                category = "ROOT_SHELL",
                action = "执行系统级 Shell 命令",
                target = arguments.take(80),
                impact = "将以特权执行底层系统命令，可能修改系统配置或文件数据",
                requiresConfirmation = true,
            )
        }

        // 1. DeepLink 风险分析
        if (toolName == "open_deeplink") {
            if (PAYMENT_SCHEMES.any { lowerArgs.contains(it) }) {
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.CRITICAL,
                    category = "PAYMENT",
                    action = "拉起外部支付/金融应用",
                    target = arguments.take(80),
                    impact = "将调用第三方支付网关或银行应用完成扣款/授权",
                    requiresConfirmation = true,
                )
            }
        }

        // 2. 文本输入风险分析
        if (toolName == "input_text") {
            val isEnter = lowerArgs.contains("\"press_enter\":true") || lowerArgs.contains("\"press_enter\": true")
            val containsPayment = PAYMENT_REGEX.containsMatchIn(lowerScreen)
            if (containsPayment) {
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.CRITICAL,
                    category = "PAYMENT_INPUT",
                    action = "在支付敏感页面输入内容或密码",
                    target = "当前屏幕输入框",
                    impact = "可能录入支付凭据或自动提交订单",
                    requiresConfirmation = true,
                )
            }

            if (isEnter && SUBMIT_REGEX.containsMatchIn(lowerArgs)) {
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.MODERATE,
                    category = "SEND_MESSAGE",
                    action = "输入内容并回车发送/提交",
                    target = arguments.take(80),
                    impact = "将直接将消息、动态或表单发送给对方或远程服务",
                    requiresConfirmation = true,
                )
            }
        }

        // 3. 点击或手势动作风险分析 (涵盖 click_element, tap, double_tap, long_press, swipe)
        if (toolName in setOf("click_element", "tap", "double_tap", "long_press", "swipe")) {
            // 支付风险（高置信度：入参或屏幕文本命中均拦截）
            if (PAYMENT_REGEX.containsMatchIn(lowerArgs) || PAYMENT_REGEX.containsMatchIn(lowerScreen)) {
                val matched = PAYMENT_REGEX.find(lowerArgs)?.value ?: PAYMENT_REGEX.find(lowerScreen)?.value ?: "支付"
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.CRITICAL,
                    category = "PAYMENT_CLICK",
                    action = "点击确认支付/扣款相关按钮",
                    target = "控件/屏幕: $matched",
                    impact = "将产生实际资金支出或扣款",
                    requiresConfirmation = true,
                )
            }

            // 删除/破坏性风险：仅针对入参中的目标元素或操作指令，杜绝因整屏包含无关"删除"选项而误拦截常规点击
            if (DESTRUCTION_REGEX.containsMatchIn(lowerArgs)) {
                val matched = DESTRUCTION_REGEX.find(lowerArgs)?.value ?: "删除"
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.HIGH,
                    category = "DELETE",
                    action = "点击删除/销毁操作",
                    target = "按钮/控件: $matched",
                    impact = "将永久删除数据或破坏既有资产",
                    requiresConfirmation = true,
                )
            }

            // 权限授权风险：仅针对入参中的目标授权按钮
            if (AUTHORIZATION_REGEX.containsMatchIn(lowerArgs)) {
                val matched = AUTHORIZATION_REGEX.find(lowerArgs)?.value ?: "授权"
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.HIGH,
                    category = "AUTHORIZATION",
                    action = "点击系统敏感权限授权",
                    target = "按钮/控件: $matched",
                    impact = "将授予应用高危系统特权",
                    requiresConfirmation = true,
                )
            }

            // 提交/发送风险：仅针对入参中的发送/提交目标按钮
            if (SUBMIT_REGEX.containsMatchIn(lowerArgs)) {
                val matched = SUBMIT_REGEX.find(lowerArgs)?.value ?: "发送"
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.MODERATE,
                    category = "SEND_OR_SUBMIT",
                    action = "点击发送或提交按钮",
                    target = "按钮/控件: $matched",
                    impact = "将发送公开或私密消息/表单",
                    requiresConfirmation = true,
                )
            }
        }

        // 4. 回车键执行分析
        if (toolName == "key_action" && lowerArgs.contains("enter")) {
            if (PAYMENT_REGEX.containsMatchIn(lowerScreen)) {
                return RiskDecision(
                    isRisk = true,
                    level = RiskLevel.CRITICAL,
                    category = "PAYMENT_ENTER",
                    action = "在支付页面触发确认回车键",
                    target = "当前屏幕焦点",
                    impact = "可能触发订单或支付的最终提交",
                    requiresConfirmation = true,
                )
            }
        }

        return RiskDecision(isRisk = false)
    }
}
