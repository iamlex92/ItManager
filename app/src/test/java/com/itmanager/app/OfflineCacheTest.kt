package com.itmanager.app

import com.itmanager.app.core.network.OfflineCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCacheTest {
    @Test
    fun onlyInventoryReadsAreKept() {
        assertTrue(OfflineCache.cacheable("/api/v1/devices"))
        assertTrue(OfflineCache.cacheable("/api/v1/floors/abc/map"))
        assertTrue(OfflineCache.cacheable("/api/v1/vlans"))
        assertFalse(OfflineCache.cacheable("/api/v1/credentials"))
        assertFalse(OfflineCache.cacheable("/api/v1/server-settings"))
        assertFalse(OfflineCache.cacheable("/api/v1/audit"))
        assertFalse(OfflineCache.cacheable("/api/v1/pcs/keys/1"))
        assertFalse(OfflineCache.cacheable("/api/v1/health"))
        assertTrue(OfflineCache.cacheable("/static/plans/floor1.svg"))
        assertFalse(OfflineCache.cacheable("/static/admin/admin.js"))
    }
}
