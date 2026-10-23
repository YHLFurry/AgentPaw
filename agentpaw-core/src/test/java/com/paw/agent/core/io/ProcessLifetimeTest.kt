package com.paw.agent.core.io

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessLifetimeTest {
    @Test fun `cancelling a blocking read destroys its process`() = runTest {
        val released = CountDownLatch(1)
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { released.countDown() }
            override fun destroyForcibly(): Process { destroy(); return this }
        }
        val read = async { ProcessLifetime.read(process) { released.await() } }
        runCurrent()
        read.cancelAndJoin()
        assertTrue(released.count == 0L)
    }
}
