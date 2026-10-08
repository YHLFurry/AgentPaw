package com.paw.agent.core.agent

/**
 * Utility for masking sensitive data (passwords, tokens, API keys, payment info, large base64 dumps)
 * in user-visible logs, tool outputs, and diagnostic messages.
 */
object SensitiveDataMasker {

    private val BASE64_IMAGE_REGEX = Regex(""""image_base64"\s*:\s*"[^"]{40,}"""")

    // 匹配 sk- 或 AIza 开头的常见 API Key（移除结尾 \b，支持以 - 或 _ 结尾的 key 边界）
    private val KNOWN_API_KEY_REGEX = Regex("""(?i)\b(sk-[a-zA-Z0-9_\-]{8,}|AIza[0-9A-Za-z\-_]{35})""")

    // 匹配 HTTP 头部 Authorization 凭据（Bearer, Basic 等）
    private val AUTH_HEADER_REGEX = Regex("""(?i)\b(Bearer|Basic)\s+([a-zA-Z0-9_\-\.+=/]{10,})""")

    // 严格完全遮蔽：密码、PIN、Passcode、CVV、验证码等核心凭据，一律替换为 ***，杜绝首尾明文泄漏
    private val STRICT_PASSWORD_REGEX = Regex(
        """(?i)(["']?(?:password|passwd|pwd|passcode|pin|cvv|验证码|verification[_\-]?code)["']?\s*[:=]\s*["']?)([^"',\s}\]]{3,})((?:["',\s}\]]|$))"""
    )

    // 键值对形式的长凭据 (Token, API Key, Secret)，长度至少 8 位，杜绝误伤 {"auth": true} 等诊断布尔值
    private val TOKEN_KEY_VALUE_REGEX = Regex(
        """(?i)(["']?(?:api[_\-]?key|access[_\-]?key|secret|auth[_\-]?token|access[_\-]?token|token)["']?\s*[:=]\s*["']?)([^"',\s}\]]{8,})((?:["',\s}\]]|$))"""
    )

    // 匹配 URL 查询参数中的密码与验证码
    private val QUERY_PARAM_PASSWORD_REGEX = Regex("""(?i)([?&](?:password|passwd|pwd|pin|cvv)=)([^&\s]{3,})""")

    // 匹配 URL 查询参数中的长 Token / API Key
    private val QUERY_PARAM_TOKEN_REGEX = Regex("""(?i)([?&](?:api[_\-]?key|access[_\-]?key|token|secret)=)([^&\s]{8,})""")

    // 匹配银行卡号
    private val BANK_CARD_REGEX = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b""")

    private fun maskLongToken(v: String): String =
        if (v.length <= 8) "***" else v.take(3) + "..." + v.takeLast(3)

    fun mask(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw

        // 1. 剥离日志中超长 base64 图片数据，保持日志紧凑
        text = text.replace(BASE64_IMAGE_REGEX, """"image_base64": "[IMAGE_ATTACHMENT_OMITTED]"""")

        // 2. 脱敏已知前缀的 API Key (sk-..., AIza...)
        text = text.replace(KNOWN_API_KEY_REGEX) { match ->
            val v = match.value
            v.take(4) + "..." + v.takeLast(3)
        }

        // 3. 严格完全遮蔽密码、PIN、验证码 (一律 ***)
        text = text.replace(STRICT_PASSWORD_REGEX) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[3]
            "$prefix***$suffix"
        }

        // 4. 脱敏长 Token / API Key / Secret (保留前后各 3 位)
        text = text.replace(TOKEN_KEY_VALUE_REGEX) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            val suffix = match.groupValues[3]
            "$prefix${maskLongToken(value)}$suffix"
        }

        // 5. 脱敏 Authorization Bearer / Basic
        text = text.replace(AUTH_HEADER_REGEX) { match ->
            val scheme = match.groupValues[1]
            val token = match.groupValues[2]
            "$scheme ${maskLongToken(token)}"
        }

        // 6. 脱敏 URL 查询参数
        text = text.replace(QUERY_PARAM_PASSWORD_REGEX) { match ->
            val prefix = match.groupValues[1]
            "$prefix***"
        }
        text = text.replace(QUERY_PARAM_TOKEN_REGEX) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            "$prefix${maskLongToken(value)}"
        }

        // 7. 脱敏银行卡号
        text = text.replace(BANK_CARD_REGEX, "****-****-****-****")

        return text
    }
}
