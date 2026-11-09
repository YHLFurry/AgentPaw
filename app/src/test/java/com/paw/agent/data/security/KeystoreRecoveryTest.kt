package com.paw.agent.data.security

import org.junit.Assert.*
import org.junit.Test

class KeystoreRecoveryTest {
    @Test fun `invalidated key is replaced once while unrelated failures preserve it`() {
        var attempts = 0
        var resets = 0
        val encrypted = KeystoreSecretStorage.withKeyRecovery({ resets++ }) {
            if (attempts++ == 0) throw SecurityException("key unavailable", java.security.UnrecoverableKeyException())
            "ciphertext"
        }
        assertEquals("ciphertext", encrypted)
        assertEquals(1, resets)
        assertTrue(runCatching {
            KeystoreSecretStorage.withKeyRecovery({ resets++ }) { throw java.io.IOException("temporary failure") }
        }.isFailure)
        assertEquals(1, resets)
    }
}
