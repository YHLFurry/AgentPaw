package com.paw.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridPhoneControllerSecurityTest {

    @Test
    fun `PACKAGE_NAME_REGEX accepts standard Android package names`() {
        val validPackages = listOf(
            "com.paw.agent",
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.android.settings",
            "org.example.app_test",
            "a.b",
        )
        for (pkg in validPackages) {
            assertTrue("Expected valid: $pkg", HybridPhoneController.PACKAGE_NAME_REGEX.matches(pkg))
        }
    }

    @Test
    fun `PACKAGE_NAME_REGEX rejects malicious shell injection inputs`() {
        val maliciousInputs = listOf(
            "com.tencent.mm; rm -rf /",
            "com.tencent.mm && id",
            "com.tencent.mm | cat",
            "com.tencent.mm\nreboot",
            "`reboot`",
            "$(id)",
            "; monkey -p com.evil 1",
            "com.example.app\"test",
            "com.example.app'test",
            "com.example.app test",
            "> /data/local/tmp/hack",
        )
        for (input in maliciousInputs) {
            assertFalse("Expected rejected injection: $input", HybridPhoneController.PACKAGE_NAME_REGEX.matches(input))
        }
    }

    @Test
    fun `PACKAGE_NAME_REGEX rejects invalid package formats`() {
        val invalidPackages = listOf(
            "",
            "singleword",
            "123.numeric.start",
            ".leading.dot",
            "trailing.dot.",
            "double..dot",
            "com.has-dash.app",
        )
        for (pkg in invalidPackages) {
            assertFalse("Expected rejected invalid format: $pkg", HybridPhoneController.PACKAGE_NAME_REGEX.matches(pkg))
        }
    }
}
