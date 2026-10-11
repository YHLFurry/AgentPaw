package com.paw.agent.core.search

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the DuckDuckGo backend.
 *
 * These run against recorded payloads rather than the live API: the endpoint is
 * a best-effort, no-key service, and a unit test should not depend on it being
 * reachable. [DuckDuckGoSearchBackendLiveTest] covers the real call separately.
 */
class DuckDuckGoSearchBackendTest {

    private val backend = DuckDuckGoSearchBackend()

    @Test
    fun `abstract answer becomes the first result`() {
        val body = """
            {
              "Heading": "Rust (programming language)",
              "AbstractText": "Rust is a multi-paradigm, general-purpose programming language.",
              "AbstractURL": "https://en.wikipedia.org/wiki/Rust_(programming_language)",
              "RelatedTopics": [],
              "Results": []
            }
        """.trimIndent()

        val response = backend.parse("rust", body, limit = 5)

        assertEquals(1, response.results.size)
        assertEquals("Rust (programming language)", response.results.first().title)
        assertTrue(response.results.first().snippet.contains("multi-paradigm"))
        assertTrue(response.abstract.isNotBlank())
    }

    @Test
    fun `related topics are flattened`() {
        val body = """
            {
              "Heading": "Kotlin",
              "AbstractText": "Kotlin is a JVM language.",
              "RelatedTopics": [
                { "Text": "Kotlin official site", "FirstURL": "https://kotlinlang.org" },
                { "Text": "Kotlin on Wikipedia", "FirstURL": "https://en.wikipedia.org/wiki/Kotlin" }
              ],
              "Results": []
            }
        """.trimIndent()

        val response = backend.parse("kotlin", body, limit = 5)

        assertEquals(3, response.results.size)
        assertTrue(response.results.any { it.url == "https://kotlinlang.org" })
    }

    @Test
    fun `nested topic groups are traversed`() {
        val body = """
            {
              "Heading": "Java",
              "AbstractText": "Java is a language.",
              "RelatedTopics": [
                {
                  "Topics": [
                    { "Text": "Java specification", "FirstURL": "https://example.org/spec" }
                  ]
                }
              ],
              "Results": []
            }
        """.trimIndent()

        val response = backend.parse("java", body, limit = 5)

        assertTrue(response.results.any { it.url == "https://example.org/spec" })
    }

    @Test
    fun `empty payload reports no results rather than throwing`() {
        val body = """{ "Heading": "", "AbstractText": "", "RelatedTopics": [], "Results": [] }"""

        val response = backend.parse("asdkjhaskdjh", body, limit = 5)

        assertTrue(response.isEmpty)
    }

    @Test
    fun `limit caps the number of results`() {
        val body = """
            {
              "AbstractText": "answer",
              "RelatedTopics": [
                { "Text": "one", "FirstURL": "https://a" },
                { "Text": "two", "FirstURL": "https://b" },
                { "Text": "three", "FirstURL": "https://c" }
              ]
            }
        """.trimIndent()

        val response = backend.parse("x", body, limit = 2)

        assertEquals(2, response.results.size)
    }

    @Test
    fun `search retries on rate limit HTTP 429 and returns fallback notice gracefully`() = runTest {
        var callCount = 0
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("rate limit exceeded".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val backendWithMock = DuckDuckGoSearchBackend(httpClient = mockClient)
        val result = backendWithMock.search("test query")

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(2, callCount)
        assertTrue(response.results.any { it.title == "Search Notice" && it.snippet.contains("429") })
    }

    @Test
    fun `search retries and recovers when subsequent attempt succeeds`() = runTest {
        var callCount = 0
        val validJson = """
            {
              "Heading": "Kotlin",
              "AbstractText": "Kotlin language.",
              "AbstractURL": "https://kotlinlang.org",
              "RelatedTopics": [],
              "Results": []
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                if (callCount == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("rate limit".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val backendWithMock = DuckDuckGoSearchBackend(httpClient = mockClient)
        val result = backendWithMock.search("Kotlin")

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(2, callCount)
        assertEquals("Kotlin", response.results.first().title)
    }
}
