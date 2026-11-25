package com.sbkubric.robby.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutostartTest {

    @Test
    fun `starts after boot`() {
        assertTrue(Autostart.shouldStartOn("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun `starts after the app is updated`() {
        assertTrue(Autostart.shouldStartOn("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun `ignores any other broadcast`() {
        assertFalse(Autostart.shouldStartOn("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(Autostart.shouldStartOn("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(Autostart.shouldStartOn(""))
        assertFalse(Autostart.shouldStartOn(null))
    }
}
