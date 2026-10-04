package com.paw.agent.core.tool

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.model.ToolDefinition
import com.paw.agent.core.search.SearchBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Looks up facts with a keyless search backend.
 *
 * The result is formatted for a model rather than a human: a short, dense list.
 * When the backend has nothing to say, the tool says so plainly instead of
 * inviting the model to fill the gap from memory.
 */
class WebSearchTool(
    private val backend: SearchBackend,
    private val maxResults: Int = SearchBackend.DEFAULT_LIMIT,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "web_search",
        description = """
            Search the web for factual information using ${backend.name}, which
            needs no API key. Good for definitions, release facts, and current
            information. Returns titles and summaries; it does not fetch full
            pages. If the search comes back empty, say so rather than answering
            from memory.
        """.trimIndent(),
        parametersSchema = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "The search query. One or two noun phrases work best."
                },
                "limit": {
                  "type": "integer",
                  "description": "Optional number of results, 1-$maxResults."
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val request = runCatching {
            Json.parseToJsonElement(arguments).jsonObject
        }.getOrElse {
            return "Error: could not parse the arguments (${it.message})."
        }

        val query = (request["query"] as? JsonPrimitive)?.content.orEmpty().trim()
        if (query.isBlank()) return "Error: 'query' is required."

        val limit = (request["limit"] as? JsonPrimitive)
            ?.content
            ?.toIntOrNull()
            ?.coerceIn(1, maxResults)
            ?: maxResults

        if (context.isCancelled()) return "Error: cancelled before searching."

        return backend.search(query, limit).fold(
            onSuccess = { response -> format(query, response) },
            onFailure = { error ->
                "Error: search via ${backend.name} failed (${error.message})."
            },
        )
    }

    private fun format(query: String, response: com.paw.agent.core.search.SearchResponse): String {
        if (response.isEmpty) {
            return "No results for \"$query\". This backend answers entity-style " +
                "questions best; try rephrasing, or answer from your own knowledge " +
                "and say that you could not verify it."
        }

        return buildString {
            append("Search results for \"").append(query).append("\":\n")
            response.results.forEachIndexed { index, result ->
                append('\n').append(index + 1).append(". ").append(result.title).append('\n')
                append("   ").append(result.snippet.replace('\n', ' '))
                if (result.url.isNotBlank()) {
                    append("\n   ").append(result.url)
                }
            }
        }
    }
}
