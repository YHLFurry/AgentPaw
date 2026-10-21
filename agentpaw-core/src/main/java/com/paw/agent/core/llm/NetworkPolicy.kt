package com.paw.agent.core.llm

/**
 * 传输层安全策略。
 *
 * 明文 HTTP 默认禁止；资深设置可以显式放开，但「放开」被拆成两级：
 * - [allowHttp]：允许对本机/局域网地址使用 HTTP（本地模型调试的常见需求）；
 * - [allowPublicHttp]：允许对公网地址使用 HTTP（API Key 会以明文经过网络，极高危）。
 *
 * 之所以把判定放在核心模块而不是依赖 Android 的 network-security-config：
 * 配置是编译期静态的，无法随开关变化；而策略必须能随用户设置实时生效，
 * 也能被单元测试直接覆盖。平台层因此放行 cleartext，真正的闸门在这里。
 */
data class NetworkPolicy(
    val allowHttp: Boolean = false,
    val allowPublicHttp: Boolean = false,
) {
    data class Decision(
        val allowed: Boolean,
        val reason: String = "",
        val isInsecure: Boolean = false,
    )

    fun evaluate(url: String): Decision {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            return Decision(false, "地址为空")
        }
        val lower = normalized.lowercase()
        if (lower.startsWith("https://")) {
            return Decision(true)
        }
        if (!lower.startsWith("http://")) {
            return Decision(false, "仅支持 http:// 或 https:// 地址")
        }

        val isPrivate = OpenAiCompatibleClient.isLocalOrPrivateAddress(normalized)
        if (!allowHttp) {
            return Decision(
                allowed = false,
                reason = "已阻止明文 HTTP：API Key 与对话内容会以明文经过网络。" +
                    "如需连接本地/局域网模型，请在「资深设置 → 传输与执行安全」中开启「允许 HTTP 传输」。",
            )
        }
        if (!isPrivate && !allowPublicHttp) {
            return Decision(
                allowed = false,
                reason = "已阻止公网明文 HTTP：目标不是本机或局域网地址。" +
                    "确需使用时，请另行开启「允许公网明文 HTTP」并自行承担凭据泄露风险。",
            )
        }
        return Decision(
            allowed = true,
            reason = if (isPrivate) {
                "正在使用明文 HTTP 连接本地/局域网地址，仅建议在可信网络中使用"
            } else {
                "正在使用明文 HTTP 连接公网地址，API Key 可能被中间人截获"
            },
            isInsecure = true,
        )
    }
}
