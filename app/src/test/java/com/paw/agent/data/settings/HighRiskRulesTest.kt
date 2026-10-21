package com.paw.agent.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高危开关的约束必须由纯逻辑层保证：UI 只能「不让点」，
 * 而这里保证「无论谁调用，非法组合都写不进去」。
 */
class HighRiskRulesTest {

    @Test
    fun `public cleartext is rejected until http is enabled`() {
        val outcome = HighRiskRules.toggle(
            HighRiskState(),
            HighRiskToggle.ALLOW_PUBLIC_HTTP,
            enabled = true,
        )
        assertFalse(outcome.applied)
        assertNotNull(outcome.message)
        assertFalse(outcome.state.allowPublicHttp)
    }

    @Test
    fun `public cleartext is accepted after http is enabled`() {
        val step1 = HighRiskRules.toggle(HighRiskState(), HighRiskToggle.ALLOW_HTTP, true)
        assertTrue(step1.applied)

        val step2 = HighRiskRules.toggle(step1.state, HighRiskToggle.ALLOW_PUBLIC_HTTP, true)
        assertTrue(step2.applied)
        assertTrue(step2.state.allowPublicHttp)
    }

    @Test
    fun `disabling http cascades to public cleartext`() {
        val state = HighRiskState(allowHttp = true, allowPublicHttp = true)
        val outcome = HighRiskRules.toggle(state, HighRiskToggle.ALLOW_HTTP, false)

        assertTrue(outcome.applied)
        assertFalse(outcome.state.allowHttp)
        assertFalse(outcome.state.allowPublicHttp)
    }

    @Test
    fun `shell skills require root mode`() {
        val rejected = HighRiskRules.toggle(HighRiskState(), HighRiskToggle.ALLOW_SHELL_SKILLS, true)
        assertFalse(rejected.applied)
        assertFalse(rejected.state.allowShellSkills)

        val accepted = HighRiskRules.toggle(
            HighRiskState(rootModeEnabled = true),
            HighRiskToggle.ALLOW_SHELL_SKILLS,
            true,
        )
        assertTrue(accepted.applied)
        assertTrue(accepted.state.allowShellSkills)
    }

    @Test
    fun `turning root mode off clears shell permission`() {
        val state = HighRiskState(rootModeEnabled = true, allowShellSkills = true)
        val next = HighRiskRules.onRootModeChanged(state, false)
        assertFalse(next.rootModeEnabled)
        assertFalse(next.allowShellSkills)
    }

    @Test
    fun `sanitize collapses impossible combinations`() {
        val dirty = HighRiskState(
            allowHttp = false,
            allowPublicHttp = true,
            rootModeEnabled = false,
            allowShellSkills = true,
        )
        val clean = HighRiskRules.sanitize(dirty)
        assertFalse(clean.allowPublicHttp)
        assertFalse(clean.allowShellSkills)
    }

    @Test
    fun `bypass window is clamped and can be cleared`() {
        val now = 1_000_000L
        assertEquals(0L, HighRiskRules.bypassUntil(0, now))
        assertEquals(now + 15 * 60_000L, HighRiskRules.bypassUntil(15, now))
        assertEquals(
            now + AppSettings.MAX_RISK_BYPASS_MINUTES * 60_000L,
            HighRiskRules.bypassUntil(999, now),
        )
    }
}
