package com.paw.agent.core.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DuckDuckGo's keyless Instant Answer API.
 *
 * Chosen because it needs no account or API key, so the feature works out of the
 * box. The trade-off is that it answers entity-style queries ("rust", "kotlin")
 * far better than open-ended ones ("how do I sort a list in kotlin") — for the
 * latter it usually returns an empty result, which the tool reports honestly
 * rather than inventing something.
 */
class DuckDuckGoSearchBackend(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson(),
) : SearchBackend {

    override val name: String = "DuckDuckGo"

    override suspend fun search(query: String, limit: Int): Result<SearchResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = BASE_URL.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("q", query)
                    ?.addQueryParameter("format", "json")
                    ?.addQueryParameter("no_html", "1")
                    ?.addQueryParameter("skip_disambig", "1")
                    ?.build()
                    ?: error("Invalid DuckDuckGo URL")

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("DuckDuckGo returned HTTP ${response.code}")
                    }
                    parse(query, body, limit)
                }
            }
        }

    /** Exposed for unit tests: turns a raw response body into a [SearchResponse]. */
    internal fun parse(query: String, body: String, limit: Int): SearchResponse {
        val root = json.parseToJsonElement(body).jsonObject

        val abstract = root.stringOrEmpty("AbstractText")
        val heading = root.stringOrEmpty("Heading")

        val results = buildList {
            // The abstract is the highest-signal answer, so surface it first.
            if (abstract.isNotBlank()) {
                add(
                    SearchResult(
                        title = heading.ifBlank { query },
                        snippet = abstract,
                        url = root.stringOrEmpty("AbstractURL"),
                    ),
                )
            }

            // RelatedTopics may be a flat list or nested groups, and the first
            // element is sometimes a sentinel that must be skipped.
            root["RelatedTopics"]?.jsonArray?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                if (obj.containsKey("Topics")) {
                    obj["Topics"]?.jsonArray?.forEach { nested ->
                        val topic = nested as? JsonObject ?: return@forEach
                        topic.toResult()?.let(::add)
                    }
                } else {
                    obj.toResult()?.let(::add)
                }
            }

            root["Results"]?.jsonArray?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                obj.toResult()?.let(::add)
            }
        }

        return SearchResponse(
            query = query,
            results = results.distinctBy { it.url.ifBlank { it.snippet } }.take(limit),
            abstract = abstract,
        )
    }

    private fun JsonObject.toResult(): SearchResult? {
        val text = stringOrEmpty("Text")
        if (text.isBlank()) return null
        return SearchResult(
            title = stringOrEmpty("ResultTitle").ifBlank { text.take(80) },
            snippet = text,
            url = stringOrEmpty("FirstURL"),
        )
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private companion object {
        const val BASE_URL = "https://api.duckduckgo.com/"
        const val USER_AGENT = "AgentPaw/0.1 (Android; +https://github.com/YHLFurry/AgentPaw)"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
