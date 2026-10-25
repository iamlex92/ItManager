package com.itmanager.app.data.source

import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort

/** One place the app reads DCIM data from: built-in demo data or the NetAdmin server. */
interface DcimDataSource {
    suspend fun floorMap(): FloorMap
    suspend fun devices(): List<Device>
    suspend fun device(id: String): Device
    suspend fun ports(deviceId: String): List<SwitchPort>
    suspend fun printers(): List<Printer>
}
