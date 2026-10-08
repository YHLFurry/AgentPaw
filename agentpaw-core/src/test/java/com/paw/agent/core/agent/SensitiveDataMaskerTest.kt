package com.paw.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataMaskerTest {

    @Test
    fun `passwords and pins are completely masked without leaking prefix or suffix`() {
        val input = """password="SuperSecretPassword123" and pin=123456 and "verification_code": "987654""""
        val masked = SensitiveDataMasker.mask(input)
        assertFalse("Must not leak Super", masked.contains("Super"))
        assertFalse("Must not leak 123", masked.contains("Password123"))
        assertFalse("Must not leak 123456", masked.contains("123456"))
        assertFalse("Must not leak 987654", masked.contains("987654"))
        assertTrue("Contains complete mask ***", masked.contains("***"))
        assertTrue(masked.contains("""password="***""""))
        assertTrue(masked.contains("pin=***"))
        assertTrue(masked.contains(""""verification_code": "***""""))
    }

    @Test
    fun `api keys ending with hyphen or underscore are masked`() {
        val keyWithHyphen = "sk-proj-abc-123-def_xyz-"
        val input = "Using api key $keyWithHyphen in header"
        val masked = SensitiveDataMasker.mask(input)
        assertFalse("Must not leak secret key body", masked.contains("abc-123-def_xyz-"))
        assertTrue("Masks with prefix and ellipsis", masked.contains("sk-p...yz-"))
    }

    @Test
    fun `diagnostic status boolean auth true does not falsely match token mask`() {
        val input = """{"auth": true, "authenticated": true, "session": "active"}"""
        val masked = SensitiveDataMasker.mask(input)
        assertEquals("Diagnostic booleans should not be modified", input, masked)
    }

    @Test
    fun `tokens of sufficient length are masked`() {
        val input = """access_token="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9""""
        val masked = SensitiveDataMasker.mask(input)
        assertFalse("Token body should be masked", masked.contains("hbGciOiJIUzI1NiIsIn"))
        assertTrue("Masks token with ellipsis", masked.contains("..."))
    }

    @Test
    fun `bank card numbers are masked`() {
        val input = "Card: 6222-0210-5678-1234"
        val masked = SensitiveDataMasker.mask(input)
        assertFalse("Original card number should not be leaked", masked.contains("6222"))
        assertTrue("Masked with stars", masked.contains("****-****-****-****"))
    }

    @Test
    fun `authorization bearer headers are masked`() {
        val input = "Authorization: Bearer mysecretbearertoken123"
        val masked = SensitiveDataMasker.mask(input)
        assertFalse("Bearer secret token should not be leaked", masked.contains("secretbearer"))
        assertTrue("Masks bearer token", masked.contains("Bearer mys...123"))
    }
}
