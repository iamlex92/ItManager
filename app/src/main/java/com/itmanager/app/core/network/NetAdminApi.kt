package com.itmanager.app.core.network

import com.itmanager.app.core.settings.ServerSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only client for the NetAdmin REST API. Writes to switches come in a later stage. */
@Singleton
class NetAdminApi @Inject constructor(
    private val client: HttpClient,
) {
    /** Health needs no token, so the settings screen can test an address before saving it. */
    suspend fun health(baseUrl: String): HealthDto = client.get("$baseUrl/api/v1/health").body()

    suspend fun floors(s: ServerSettings): List<FloorDto> = fetch(s, "floors")

    suspend fun devices(s: ServerSettings): List<DeviceDto> = fetch(s, "devices")

    suspend fun device(s: ServerSettings, id: String): DeviceDto = fetch(s, "devices/$id")

    suspend fun ports(s: ServerSettings, deviceId: String): List<PortDto> = fetch(s, "devices/$deviceId/ports")

    suspend fun vlans(s: ServerSettings): List<VlanDto> = fetch(s, "vlans")

    suspend fun connections(s: ServerSettings): List<ConnectionDto> = fetch(s, "connections")

    suspend fun printers(s: ServerSettings): List<PrinterDto> = fetch(s, "printers")

    private suspend inline fun <reified T> fetch(s: ServerSettings, path: String): T {
        if (s.baseUrl.isBlank()) {
            throw UserFacingException("Укажите адрес сервера в настройках или включите демо-данные.")
        }
        return client.get("${s.baseUrl}/api/v1/$path") {
            if (s.apiToken.isNotBlank()) bearerAuth(s.apiToken)
        }.body()
    }
}
