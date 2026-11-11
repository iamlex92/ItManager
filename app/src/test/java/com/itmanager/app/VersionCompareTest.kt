package com.itmanager.app

import com.itmanager.app.data.model.isOlderVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun comparesDottedVersionsAndIgnoresGarbage() {
        assertTrue(isOlderVersion("1.0.9", "1.1.0"))
        assertTrue(isOlderVersion("1.1", "1.1.1"))
        assertFalse(isOlderVersion("1.1.0-debug", "1.1.0"))
        assertFalse(isOlderVersion("1.2.0", "1.1.9"))
        assertFalse(isOlderVersion(null, "1.1.0"))
        assertFalse(isOlderVersion("1.1.0", ""))
        assertFalse(isOlderVersion("dev", "1.1.0"))
    }
}
