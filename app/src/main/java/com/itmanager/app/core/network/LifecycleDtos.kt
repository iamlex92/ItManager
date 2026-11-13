package com.itmanager.app.core.network

import com.itmanager.app.data.model.Expiration
import com.itmanager.app.data.model.LicenseInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExpirationDto(
    val source: String = "",
    val title: String,
    val due: String,
    @SerialName("days_left") val daysLeft: Int = 0,
    val level: String = "ok",
    @SerialName("device_id") val deviceId: String? = null,
) {
    fun toDomain() = Expiration(source, title, due, daysLeft, level, deviceId)
}

@Serializable
data class LicenseDto(
    val id: String,
    val software: String,
    val vendor: String? = null,
    val kind: String = "per_device",
    val seats: Int? = null,
    val used: Int = 0,
    val over: Boolean = false,
    @SerialName("valid_until") val validUntil: String? = null,
    val supplier: String? = null,
    @SerialName("has_key") val hasKey: Boolean = false,
) {
    fun toDomain() = LicenseInfo(id, software, vendor, kind, seats, used, over, validUntil, supplier, hasKey)
}
