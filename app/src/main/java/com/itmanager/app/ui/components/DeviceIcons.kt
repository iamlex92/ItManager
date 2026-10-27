package com.itmanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeviceUnknown
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.itmanager.app.data.model.DeviceType

/** The pictogram of a device type, on the map, in lists and in the legend. */
val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.SWITCH -> Icons.Outlined.Lan
        DeviceType.ROUTER -> Icons.Outlined.Router
        DeviceType.FIREWALL -> Icons.Outlined.Security
        DeviceType.WORKSTATION -> Icons.Outlined.Computer
        DeviceType.SERVER -> Icons.Outlined.Dns
        DeviceType.PRINTER -> Icons.Outlined.Print
        DeviceType.SOCKET -> Icons.Outlined.SettingsInputComponent
        DeviceType.PATCH_PANEL -> Icons.Outlined.ViewModule
        DeviceType.CAMERA -> Icons.Outlined.Videocam
        DeviceType.ACCESS_POINT -> Icons.Outlined.Wifi
        DeviceType.UPS -> Icons.Outlined.BatteryChargingFull
        DeviceType.PHONE -> Icons.Outlined.Call
        DeviceType.VIRTUAL -> Icons.Outlined.CloudQueue
        DeviceType.RACK -> Icons.Outlined.Storage
        DeviceType.UNKNOWN -> Icons.Outlined.DeviceUnknown
    }

/**
 * The colour of a type's pictogram: one hue per kind, so a camera and a printer differ at a glance
 * even when the label is hidden. Green and red stay free for the online dot.
 */
val DeviceType.tint: Color
    get() = when (this) {
        DeviceType.SWITCH -> Color(0xFF4C9EFF)
        DeviceType.ROUTER -> Color(0xFF64D2FF)
        DeviceType.FIREWALL -> Color(0xFFFF8A80)
        DeviceType.WORKSTATION -> Color(0xFFE6EDF5)
        DeviceType.SERVER -> Color(0xFFBF8CFF)
        DeviceType.PRINTER -> Color(0xFF5AD4B0)
        DeviceType.CAMERA -> Color(0xFFFF9F43)
        DeviceType.ACCESS_POINT -> Color(0xFFFFD60A)
        DeviceType.UPS -> Color(0xFFD4E157)
        DeviceType.PHONE -> Color(0xFFFF7EB6)
        DeviceType.VIRTUAL -> Color(0xFF8E9BFF)
        DeviceType.RACK -> Color(0xFFFFB86B)
        DeviceType.SOCKET, DeviceType.PATCH_PANEL -> Color(0xFFAEB7C4)
        DeviceType.UNKNOWN -> Color(0xFF8D97A5)
    }

/** Types in the order pickers and the legend list them: network first, cabling and the unknown last. */
val DEVICE_TYPES_IN_ORDER = listOf(
    DeviceType.SWITCH, DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.ACCESS_POINT, DeviceType.SERVER,
    DeviceType.VIRTUAL, DeviceType.RACK, DeviceType.WORKSTATION, DeviceType.PRINTER, DeviceType.CAMERA, DeviceType.PHONE, DeviceType.UPS,
    DeviceType.PATCH_PANEL, DeviceType.SOCKET, DeviceType.UNKNOWN,
)
