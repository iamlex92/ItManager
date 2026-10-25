package com.itmanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.itmanager.app.data.model.DeviceType

val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.SWITCH -> Icons.Outlined.Hub
        DeviceType.WORKSTATION -> Icons.Outlined.Computer
        DeviceType.SERVER -> Icons.Outlined.Dns
        DeviceType.PRINTER -> Icons.Outlined.Print
        DeviceType.SOCKET -> Icons.Outlined.SettingsInputComponent
        DeviceType.PATCH_PANEL -> Icons.Outlined.Lan
        DeviceType.CAMERA -> Icons.Outlined.Videocam
        DeviceType.ACCESS_POINT -> Icons.Outlined.Wifi
        DeviceType.UNKNOWN -> Icons.Outlined.Router
    }
