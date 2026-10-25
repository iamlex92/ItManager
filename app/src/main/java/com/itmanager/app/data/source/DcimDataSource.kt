package com.itmanager.app.data.source

import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort
import kotlinx.coroutines.flow.Flow

/** One place the app reads DCIM data from: built-in demo data or the NetAdmin server. */
interface DcimDataSource {
    suspend fun floorMap(): FloorMap
    suspend fun devices(): List<Device>
    suspend fun device(id: String): Device
    suspend fun ports(deviceId: String): List<SwitchPort>
    suspend fun printers(): List<Printer>

    /** Raw SVG of a floor plan. */
    suspend fun planSvg(url: String): String

    /** Live port and device status for one switch, or for every device with [com.itmanager.app.data.model.ALL_DEVICES]. */
    fun live(deviceId: String): Flow<LiveEvent>
}
