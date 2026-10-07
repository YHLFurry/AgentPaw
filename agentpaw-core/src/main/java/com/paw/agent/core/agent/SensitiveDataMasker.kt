package com.paw.agent.core.agent

/**
 * Utility for masking sensitive data (passwords, tokens, API keys, payment info, large base64 dumps)
 * in user-visible logs, tool outputs, and diagnostic messages.
 */
object SensitiveDataMasker {

    private val API_KEY_REGEX = Regex("""(?i)(sk-[a-zA-Z0-9_\-]{8,})""")
    private val BASE64_IMAGE_REGEX = Regex(""""image_base64"\s*:\s*"[^"]{40,}"""")
    private val GENERIC_TOKEN_REGEX = Regex("""(?i)(bearer\s+[a-zA-Z0-9_\-\.]{15,}|token\s*[:=]\s*["']?[a-zA-Z0-9_\-]{16,}["']?)""")
    private val PASSWORD_REGEX = Regex("""(?i)("?(?:password|passwd|pwd|pay_password|payment_pwd)"?\s*[:=]\s*)"?([^"',\s}\]]{3,})"?([",\s}\]])""")
    private val BANK_CARD_REGEX = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b""")

    fun mask(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw

        // 1. Strip raw base64 data to keep logs compact and readable
        text = text.replace(BASE64_IMAGE_REGEX, """"image_base64": "[IMAGE_ATTACHMENT_OMITTED]"""")

        // 2. Mask API keys
        text = text.replace(API_KEY_REGEX) { match ->
            val v = match.value
            v.take(5) + "..." + v.takeLast(3)
        }

        // 3. Mask tokens
        text = text.replace(GENERIC_TOKEN_REGEX) { match ->
            val v = match.value
            v.take(8) + "***[MASKED_TOKEN]"
        }

        // 4. Mask passwords
        text = text.replace(PASSWORD_REGEX) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[3]
            "$prefix\"***\"$suffix"
        }

        // 5. Mask bank card numbers
        text = text.replace(BANK_CARD_REGEX, "****-****-****-****")

        return text
    }
}
