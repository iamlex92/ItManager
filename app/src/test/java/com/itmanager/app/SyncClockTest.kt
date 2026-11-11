package com.itmanager.app

import com.itmanager.app.ui.navigation.syncClock
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncClockTest {
    @Test
    fun formatsTheServerTimeAndIgnoresGarbage() {
        assertNotNull(syncClock("2026-11-11T07:44:20Z"))
        assertNull(syncClock(null))
        assertNull(syncClock("yesterday"))
    }
}
