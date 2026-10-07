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
    fun `isLocalOrPrivateAddress rejects public hosts and out of range IPs`() {
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://api.openai.com/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://example.com/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://8.8.8.8:8080/v1"))
        // 172.15 and 172.32 are outside RFC 1918 range
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.15.1.1:11434/v1"))
        assertFalse(OpenAiCompatibleClient.isLocalOrPrivateAddress("http://172.32.1.1:11434/v1"))
    }
}
