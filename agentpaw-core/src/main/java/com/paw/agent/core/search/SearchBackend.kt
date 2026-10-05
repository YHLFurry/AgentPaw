package com.paw.agent.core.search

/** One search hit, normalised across providers. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String = "",
)

/** A page of results plus whatever the provider reported. */
data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    /** Provider-side answer, when it has one (DuckDuckGo often does). */
    val abstract: String = "",
) {
    val isEmpty: Boolean get() = results.isEmpty() && abstract.isBlank()
}

/**
 * A search source.
 *
 * Keeping this an interface means the tool does not care whether results come
 * from DuckDuckGo, Tavily, or a future on-device index.
 */
interface SearchBackend {

    /** Human-readable name, surfaced in error messages. */
    val name: String

    /**
     * Runs a query.
     *
     * @param limit maximum number of results to return.
     * @return a failure for transport or provider errors, so the agent can
     *   report the reason instead of seeing an exception.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): Result<SearchResponse>

    companion object {
        const val DEFAULT_LIMIT = 5
    }
}
