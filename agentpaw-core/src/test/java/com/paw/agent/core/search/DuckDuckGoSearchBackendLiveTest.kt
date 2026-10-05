package com.paw.agent.core.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Calls the real DuckDuckGo endpoint.
 *
 * Skipped automatically when the network is unavailable, so it never turns a
 * flaky connection into a red build. Run it explicitly to check the live
 * integration:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests '*LiveTest' -Dpaus.network=true
 * ```
 */
class DuckDuckGoSearchBackendLiveTest {

    @Test
    fun `live search returns results for an entity query`() = runTest {
        assumeTrue(
            "network unavailable, skipping",
            isNetworkAvailable(),
        )

        val response = DuckDuckGoSearchBackend().search("rust programming language")

        assertTrue("expected a successful call", response.isSuccess)
        val payload = response.getOrNull()
        assertTrue("expected some content for 'rust'", payload != null && !payload.isEmpty)
    }

    private fun isNetworkAvailable(): Boolean = runCatching {
        InetAddress.getByName("api.duckduckgo.com").isReachable(3000)
    }.getOrDefault(false)
}
