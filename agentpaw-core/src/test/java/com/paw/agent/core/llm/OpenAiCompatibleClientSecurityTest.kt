package com.paw.agent.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientSecurityTest {

    @Test
    fun `isLocalOrPrivateAddress permits localhost, loopback, emulator host, and mDNS`() {
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://localhost:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://127.0.0.1:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://10.0.2.2:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://ollama-server.local:11434"))
        // IPv6 loopback
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[::1]:11434/v1"))
    }

    @Test
    fun `isLocalOrPrivateAddress permits private LAN IPv4 subnets`() {
        // 192.168.0.0/16
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://192.168.1.100:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://192.168.0.1:8000"))
        // 10.0.0.0/8
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://10.1.2.3:11434/v1"))
        // 172.16.0.0 - 172.31.255.255
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.16.0.1:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.25.10.20:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.31.255.254:11434/v1"))
    }

    @Test
    fun `isLocalOrPrivateAddress permits IPv6 ULA and Link-Local subnets`() {
        // Unique Local Address fc00::/7 (fc00... - fdff...)
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[fc00::1]:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[fd12:3456:789a::1]:8080/v1"))
        // Link-Local fe80::/10
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[fe80::1]:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[fe80::a00:27ff:fe8a:e10c]:8000"))
    }

    @Test
    fun `isLocalOrPrivateAddress rejects public hosts and out of range IPs`() {
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://api.openai.com/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://example.com/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://8.8.8.8:8080/v1"))
        // Public IPv6 addresses must be rejected
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[2001:db8::1]:11434/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://[2606:4700:4700::1111]:443"))
        // Spoofed domain names that prefix private subnets or start with fc/fd must be rejected
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://192.168.attacker.com/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://10.evil.tld/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.16.malicious.net/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://fda.gov/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://fcebook.com/v1"))
        // 172.15 and 172.32 are outside RFC 1918 range
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.15.1.1:11434/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.32.1.1:11434/v1"))
    }
}
