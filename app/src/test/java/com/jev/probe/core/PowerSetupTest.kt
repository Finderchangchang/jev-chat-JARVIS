package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the setup verdict and the autostart route table. Both are pure
 * logic, so this runs without a phone.
 */
class PowerSetupTest {

    @Test
    fun `ready only when all four hold`() {
        assertTrue(PowerSetup.verdict(true, true, true, true).ready)
        assertFalse(PowerSetup.verdict(false, true, true, true).ready)
        assertFalse(PowerSetup.verdict(true, false, true, true).ready)
        assertFalse(PowerSetup.verdict(true, true, false, true).ready)
        assertFalse(
            "an unrestricted battery state is part of ready, not a footnote",
            PowerSetup.verdict(true, true, true, false).ready)
    }

    @Test
    fun `missing lists only what is actually missing, in setup order`() {
        assertEquals(emptyList<String>(), PowerSetup.verdict(true, true, true, true).missing)
        assertEquals(
            listOf("无障碍权限", "悬浮窗权限", "判断接口密钥", "省电无限制"),
            PowerSetup.verdict(false, false, false, false).missing)
        assertEquals(
            listOf("判断接口密钥", "省电无限制"),
            PowerSetup.verdict(true, true, false, false).missing)
        assertEquals(
            listOf("省电无限制"),
            PowerSetup.verdict(true, true, true, false).missing)
    }

    @Test
    fun `the MIUI and HyperOS route is tried first`() {
        val first = PowerSetup.AUTOSTART_ROUTES.first()
        assertEquals("com.miui.securitycenter", first.pkg)
        assertTrue(
            "HyperOS is the target of this setup flow",
            first.cls.contains("autostart", ignoreCase = true))
    }

    @Test
    fun `every autostart route is complete`() {
        assertTrue("there has to be somewhere to send the user", PowerSetup.AUTOSTART_ROUTES.isNotEmpty())
        for (route in PowerSetup.AUTOSTART_ROUTES) {
            assertTrue("blank package in " + route.label, route.pkg.isNotBlank())
            assertTrue("blank class in " + route.label, route.cls.isNotBlank())
            assertTrue("blank label", route.label.isNotBlank())
            assertTrue("class must be fully qualified: " + route.cls, route.cls.contains('.'))
        }
    }
}
