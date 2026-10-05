package com.paw.agent.core.llm

/**
 * Failures surfaced by [LlmClient]. Kept separate from transport exceptions so the
 * UI can react to each case (retry, prompt for a key, show a message) without
 * string-matching on exception text.
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No usable config: missing base URL, model, or API key. */
    class NotConfigured(val reason: String) : LlmException(reason)

    /** The endpoint was unreachable, or the request timed out. */
    class Network(cause: Throwable?) :
        LlmException("Network error: ${cause?.message ?: "unknown"}", cause)

    /**
     * The server answered with a non-2xx status.
     *
     * @param unauthorized true for 401/403, which usually means a bad API key.
     */
    class Http(
        val code: Int,
        val body: String?,
        val unauthorized: Boolean = false,
    ) : LlmException("HTTP $code${if (unauthorized) " (unauthorized)" else ""}: ${body.orEmpty().take(300)}")

    /** The response could not be parsed. */
    class Malformed(cause: Throwable?) :
        LlmException("Malformed response: ${cause?.message}", cause)

    /** The provider returned a 200 but the stream carried no content. */
    class EmptyResponse : LlmException("Provider returned an empty response")
}
