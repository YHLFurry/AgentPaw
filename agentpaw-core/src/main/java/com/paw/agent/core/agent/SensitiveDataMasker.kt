package com.paw.agent.core.agent

/**
 * Utility for masking sensitive data (passwords, tokens, API keys, payment info, large base64 dumps)
 * in user-visible logs, tool outputs, and diagnostic messages.
 */
object SensitiveDataMasker {

    private val BASE64_IMAGE_REGEX = Regex(""""image_base64"\s*:\s*"[^"]{40,}"""")

    // 匹配 sk- 或 AIza 开头的常见 API Key
    private val KNOWN_API_KEY_REGEX = Regex("""(?i)\b(sk-[a-zA-Z0-9_\-]{8,}|AIza[0-9A-Za-z\-_]{35})\b""")

    // 匹配通用 Authorization / Token 凭据（Bearer, Basic 等）
    private val AUTH_HEADER_REGEX = Regex("""(?i)\b(?:Bearer|Basic)\s+([a-zA-Z0-9_\-\.+=/]{10,})\b""")

    // 匹配通用键值对形态的凭据：如 password=..., {"api_key":"..."}, token: "..."
    private val SECRET_KEY_VALUE_REGEX = Regex(
        """(?i)(["']?(?:api[_\-]?key|access[_\-]?key|secret|token|passwd|password|pwd|passcode|pin|cvv|auth|authorization|verification[_\-]?code|验证码)["']?\s*[:=]\s*["']?)([^\s"',}\]]{3,})((?:["',\s}\]]|$))"""
    )

    // 匹配 URL 查询参数中的敏感字段：?key=...&token=...
    private val QUERY_PARAM_REGEX = Regex("""(?i)([?&](?:api[_\-]?key|access[_\-]?key|token|password|pwd|secret|key)=)([^&\s]{3,})""")

    // 匹配银行卡号
    private val BANK_CARD_REGEX = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b""")

    private fun maskValue(v: String): String {
        return if (v.length <= 4) "***" else v.take(2) + "***" + v.takeLast(2)
    }

    fun mask(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw

        // 1. 剥离日志中超长 base64 图片数据，保持日志紧凑
        text = text.replace(BASE64_IMAGE_REGEX, """"image_base64": "[IMAGE_ATTACHMENT_OMITTED]"""")

        // 2. 脱敏特定前缀的 API Key
        text = text.replace(KNOWN_API_KEY_REGEX) { match ->
            val v = match.value
            v.take(4) + "..." + v.takeLast(3)
        }

        // 3. 脱敏 Authorization Bearer / Basic
        text = text.replace(AUTH_HEADER_REGEX) { match ->
            val scheme = match.value.substringBefore(" ")
            val token = match.groupValues[1]
            "$scheme ${maskValue(token)}"
        }

        // 4. 脱敏键值对格式的密码、Token、API Key、验证码等
        text = text.replace(SECRET_KEY_VALUE_REGEX) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            val suffix = match.groupValues[3]
            "$prefix${maskValue(value)}$suffix"
        }

        // 5. 脱敏 URL 查询参数中的凭据
        text = text.replace(QUERY_PARAM_REGEX) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            "$prefix${maskValue(value)}"
        }

        // 6. 脱敏银行卡号
        text = text.replace(BANK_CARD_REGEX, "****-****-****-****")

        return text
    }
}
