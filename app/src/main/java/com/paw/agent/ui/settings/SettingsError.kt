package com.paw.agent.ui.settings

import com.paw.agent.R
import com.paw.agent.core.llm.LlmException

/** Keep actionable categories separate from provider diagnostics. */
internal fun settingsErrorResource(error: Throwable): Int {
    val causes = generateSequence(error) { it.cause }.toList()
    return when {
        causes.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> R.string.error_connection_timeout_help
        error is LlmException.Http && error.unauthorized -> R.string.error_auth_help
        error is LlmException.Http && error.code == 429 -> R.string.error_rate_limit_help
        error is LlmException.Http -> R.string.error_provider_help
        causes.any { it is java.io.IOException } || error is LlmException.Network -> R.string.error_network_help
        error is LlmException.NotConfigured || error is IllegalArgumentException -> R.string.error_configuration_help
        else -> R.string.error_provider_help
    }
}
