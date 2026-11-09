package com.paw.agent.core.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import com.paw.agent.core.io.BoundedIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

                var lastException: Throwable? = null
                for (attempt in 1..2) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val (code, body) = readResponse(httpClient.newCall(request))
                        run {
                            if (code in 200..299) {
                                return@withContext Result.success(parse(query, body, limit))
                            }
                            if (code == 429 || code == 403) {
                                if (attempt == 2) {
                                    return@withContext Result.success(
                                        SearchResponse(
                                            query = query,
                                            results = listOf(
                                                SearchResult(
                                                    title = "Search Notice",
                                                    snippet = "DuckDuckGo search service is temporarily rate-limited or unavailable (HTTP ${code}).",
                                                    url = "",
                                                ),
                                            ),
                                            abstract = "Search rate-limited (HTTP ${code}).",
                                        ),
                                    )
                                }
                            } else {
                                error("DuckDuckGo returned HTTP ${code}")
                            }
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt == 2) throw e
                    }
                    if (attempt < 2) {
                        kotlinx.coroutines.delay(500)
                    }
                }
                throw lastException ?: IllegalStateException("Search failed after retry")
            }.onFailure { if (it is CancellationException) throw it }
        }

    private suspend fun readResponse(call: Call): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        it.code to it.body?.byteStream()?.use { stream ->
                            String(BoundedIO.readBytes(stream, 1024 * 1024), Charsets.UTF_8)
                        }.orEmpty()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
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
