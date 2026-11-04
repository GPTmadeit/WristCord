package com.wristcord

import com.wristcord.shared.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `semver ordering with pre-releases`() {
        assertTrue(Updater.isNewer("v2.0.0-beta.1", "1.3.0"))
        assertTrue(Updater.isNewer("v2.0.0-beta.2", "2.0.0-beta.1"))
        assertTrue(Updater.isNewer("v2.0.0-beta.10", "2.0.0-beta.9"))
        assertTrue(Updater.isNewer("v2.0.0", "2.0.0-beta.3"))
        assertTrue(Updater.isNewer("v2.0.1", "2.0.0"))
        assertFalse(Updater.isNewer("v2.0.0-beta.1", "2.0.0"))
        assertFalse(Updater.isNewer("v2.0.0-beta.1", "2.0.0-beta.1"))
        assertFalse(Updater.isNewer("v1.3.0", "2.0.0-beta.1"))
        assertTrue(Updater.isPrerelease("2.0.0-beta.1"))
        assertFalse(Updater.isPrerelease("1.3.0"))
    }
}
