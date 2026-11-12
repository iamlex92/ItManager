package com.itmanager.app

import com.itmanager.app.core.network.VlanCleanupDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class VlanCleanupTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the server answer becomes labels that skip default names`() {
        val dto = json.decodeFromString(
            VlanCleanupDto.serializer(),
            """{"dry_run":true,"count":3,"vlans":[{"vlan_id":30,"name":"Cameras"},{"vlan_id":31,"name":"VLAN 31"},{"vlan_id":32,"name":""}]}""",
        )
        val found = dto.toDomain()
        assertEquals(3, found.vlans.size)
        assertEquals(listOf("30 «Cameras»", "31", "32"), found.vlans.map { it.label })
    }
}
