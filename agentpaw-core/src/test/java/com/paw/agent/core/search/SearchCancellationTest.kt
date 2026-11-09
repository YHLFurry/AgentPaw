package com.paw.agent.core.search

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SearchCancellationTest {
    @Test(timeout = 6000) fun `cancelling search closes a stalled call promptly`() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            if (chain.call().isCanceled()) cancelled.countDown()
            throw IOException("closed")
        }.build()
        val job = launch(Dispatchers.Default) { DuckDuckGoSearchBackend(http).search("test", 1) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            job.cancel()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}
