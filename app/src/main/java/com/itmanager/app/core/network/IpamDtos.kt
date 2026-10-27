package com.itmanager.app.core.network

import com.itmanager.app.data.model.IpamCheck
import com.itmanager.app.data.model.IpamConflict
import com.itmanager.app.data.model.IpamHost
import com.itmanager.app.data.model.IpamOverview
import com.itmanager.app.data.model.IpamSubnet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IpamHostDto(@SerialName("device_id") val deviceId: String = "", val name: String = "", val ip: String = "")

@Serializable
data class IpamSubnetDto(
    @SerialName("vlan_id") val vlanId: Int,
    val name: String = "",
    val cidr: String = "",
    val total: Int = 0,
    val used: Int = 0,
    val free: Int = 0,
    @SerialName("next_free") val nextFree: List<String> = emptyList(),
    val hosts: List<IpamHostDto> = emptyList(),
)

@Serializable
data class IpamConflictDto(
    val kind: String = "",
    val ip: String = "",
    val devices: List<String> = emptyList(),
    val detail: String = "",
)

@Serializable
data class IpamDto(
    val subnets: List<IpamSubnetDto> = emptyList(),
    val conflicts: List<IpamConflictDto> = emptyList(),
) {
    fun toDomain() = IpamOverview(
        subnets.map { s ->
            IpamSubnet(
                s.vlanId, s.name, s.cidr, s.total, s.used, s.free, s.nextFree,
                s.hosts.map { IpamHost(it.deviceId, it.name, it.ip) },
            )
        },
        conflicts.map { IpamConflict(it.kind, it.ip, it.devices, it.detail) },
    )
}

@Serializable
data class IpamCheckDto(
    val valid: Boolean = false,
    val free: Boolean = false,
    val owners: List<String> = emptyList(),
    @SerialName("vlan_id") val vlanId: Int? = null,
    val problems: List<String> = emptyList(),
) {
    fun toDomain() = IpamCheck(valid, free, owners, vlanId, problems)
}
