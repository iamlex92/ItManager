package com.itmanager.app.data.model

data class IpamHost(val deviceId: String, val name: String, val ip: String)

/** One VLAN subnet: how full it is and which addresses are free next. */
data class IpamSubnet(
    val vlanId: Int,
    val name: String,
    val cidr: String,
    val total: Int,
    val used: Int,
    val free: Int,
    val nextFree: List<String>,
    val hosts: List<IpamHost>,
)

data class IpamConflict(val kind: String, val ip: String, val devices: List<String>, val detail: String)

data class IpamOverview(val subnets: List<IpamSubnet>, val conflicts: List<IpamConflict>)

data class IpamCheck(val valid: Boolean, val free: Boolean, val owners: List<String>, val vlanId: Int?, val problems: List<String>)
