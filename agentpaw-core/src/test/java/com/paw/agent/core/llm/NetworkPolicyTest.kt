package com.paw.agent.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输策略：默认严格，放开分两级，且公网明文必须单独确认。
 */
class NetworkPolicyTest {

    private val strict = NetworkPolicy()

    @Test
    fun `https is always allowed`() {
        assertTrue(strict.evaluate("https://api.openai.com/v1").allowed)
        assertTrue(strict.evaluate("https://192.168.1.10:8443/v1").allowed)
    }

    @Test
    fun `cleartext is denied by default even for localhost`() {
        val local = strict.evaluate("http://127.0.0.1:11434/v1")
        assertFalse(local.allowed)
        assertTrue(local.reason.contains("资深设置"))

        assertFalse(strict.evaluate("http://192.168.1.10:8080/v1").allowed)
        assertFalse(strict.evaluate("http://api.example.com/v1").allowed)
    }

    @Test
    fun `allowing http permits private hosts but still blocks public hosts`() {
        val policy = NetworkPolicy(allowHttp = true)

        val private = policy.evaluate("http://192.168.1.10:8080/v1")
        assertTrue(private.allowed)
        assertTrue(private.isInsecure)

        assertTrue(policy.evaluate("http://10.0.2.2:11434/v1").allowed)
        assertTrue(policy.evaluate("http://localhost:8080/v1").allowed)

        val public = policy.evaluate("http://api.example.com/v1")
        assertFalse(public.allowed)
        assertTrue(public.reason.contains("公网"))
    }

    @Test
    fun `public cleartext requires the second switch`() {
        val policy = NetworkPolicy(allowHttp = true, allowPublicHttp = true)
        val decision = policy.evaluate("http://api.example.com/v1")
        assertTrue(decision.allowed)
        assertTrue(decision.isInsecure)
    }

    @Test
    fun `unsupported schemes and empty urls are rejected`() {
        assertFalse(strict.evaluate("").allowed)
        assertFalse(strict.evaluate("ftp://example.com/v1").allowed)
        assertFalse(strict.evaluate("api.example.com/v1").allowed)
    }

    @org.junit.Test fun `mapped IPv6 private addresses are allowed without opening public mapped addresses`() {
        val policy = NetworkPolicy(allowHttp = true)
        for (host in listOf("::ffff:127.0.0.1", "::ffff:192.168.2.3", "::ffff:c0a8:203", "fd12::1234")) {
            org.junit.Assert.assertTrue(host, policy.evaluate("http://[$host]:11434/v1").allowed)
        }
        org.junit.Assert.assertFalse(policy.evaluate("http://[::ffff:8.8.8.8]:11434/v1").allowed)
    }

}
