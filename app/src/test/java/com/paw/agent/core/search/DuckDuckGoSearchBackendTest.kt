package com.paw.agent.core.search

import kotlinx.coroutines.test.runTest
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
}
