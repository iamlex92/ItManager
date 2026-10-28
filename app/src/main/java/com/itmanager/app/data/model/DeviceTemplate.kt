package com.itmanager.app.data.model

/** A saved preset: creating a device from it fills the type, vendor, model, rack height and ports. */
data class DeviceTemplate(
    val id: String,
    val name: String,
    val type: DeviceType,
    val vendor: String?,
    val model: String?,
    val portCount: Int,
    val portPrefix: String,
    val portSpeedMbps: Int,
    val rackH: Int?,
    val comment: String?,
)

data class DeviceTemplateDraft(
    val name: String,
    val type: DeviceType,
    val vendor: String,
    val model: String,
    val portCount: Int,
    val portPrefix: String,
    val rackH: Int?,
)

/** A place on a floor plan, in map pixels: where a new device is put. */
data class MapPoint(val floorId: String, val x: Float, val y: Float)
