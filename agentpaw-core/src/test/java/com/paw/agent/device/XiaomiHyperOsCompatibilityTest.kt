package com.paw.agent.device

import com.paw.agent.device.root.RootController
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiHyperOsCompatibilityTest {

    @Test
    fun `isXiaomiOrHyperOs executes cleanly without crash`() {
        val isXiaomi = DevicePermissionManager.isXiaomiOrHyperOs()
        assertNotNull(isXiaomi)
    }

    @Test
    fun `stripCrLfIfPng restores mangled PNG stream correctly`() {
        val root = RootController()
        // Standard PNG header: 89 50 4E 47 0D 0A 1A 0A
        val validPng = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0A.toByte(),
        )
        // If not mangled, stripCrLfIfPng returns null
        assertNull(root.stripCrLfIfPng(validPng))

        // Mangled PNG header where \n became \r\n (0x0D 0x0A):
        // 89 50 4E 47 0D 0D 0A 1A 0D 0A + 00 00 0D 0A
        val mangledPng = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(),
            0x0D.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x0A.toByte(),
        )
        val restored = root.stripCrLfIfPng(mangledPng)
        assertNotNull(restored)
        assertArrayEquals(validPng, restored)
    }
}
