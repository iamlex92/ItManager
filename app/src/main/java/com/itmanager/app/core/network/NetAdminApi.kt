package com.itmanager.app.core.network

import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.data.model.PortChange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Client for the NetAdmin REST API. Writes always carry an explicit "confirm": the app only sends them
 * after the user has seen the exact commands, and the server refuses them on real hardware unless enabled.
 */
@Singleton
class NetAdminApi @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {
    /** Health needs no token, so the settings screen can test an address before saving it. */
    suspend fun health(baseUrl: String): HealthDto = client.get("$baseUrl/api/v1/health").body()

    /** Floor plan SVG; served from /static without a token. */
    suspend fun text(url: String): String = client.get(url).bodyAsText()

    suspend fun floors(s: ServerSettings): List<FloorDto> = fetch(s, "floors")

    suspend fun devices(s: ServerSettings): List<DeviceDto> = fetch(s, "devices")

    suspend fun device(s: ServerSettings, id: String): DeviceDto = fetch(s, "devices/$id")

    suspend fun ports(s: ServerSettings, deviceId: String): List<PortDto> = fetch(s, "devices/$deviceId/ports")

    suspend fun vlans(s: ServerSettings): List<VlanDto> = fetch(s, "vlans")

    suspend fun connections(s: ServerSettings): List<ConnectionDto> = fetch(s, "connections")

    suspend fun printers(s: ServerSettings): List<PrinterDto> = fetch(s, "printers")

    suspend fun previewPortConfig(s: ServerSettings, portId: String, change: PortChange): CommandsDto =
        post(s, "ports/$portId/config/preview", change.toBody())

    suspend fun applyPortConfig(s: ServerSettings, portId: String, change: PortChange, confirmUplink: Boolean): PortConfigResultDto =
        post(s, "ports/$portId/config", change.toBody(confirm = true, confirmUplink = confirmUplink))

    suspend fun bouncePort(s: ServerSettings, portId: String, confirmUplink: Boolean): CommandsDto =
        post(s, "ports/$portId/bounce", ConfirmBody(confirm = true, confirmUplink = confirmUplink))

    suspend fun writeMemory(s: ServerSettings, deviceId: String): CommandsDto =
        post(s, "devices/$deviceId/write-memory", ConfirmBody(confirm = true))

    /** Reads running-config and stores it; read-only on the switch. */
    suspend fun backupConfig(s: ServerSettings, deviceId: String) {
        guarded { client.post("${requireBase(s)}/api/v1/devices/$deviceId/configs/backup") { auth(s) } }
    }

    suspend fun configDiff(s: ServerSettings, deviceId: String): ConfigDiffDto =
        guarded { client.get("${requireBase(s)}/api/v1/devices/$deviceId/configs/diff") { auth(s) }.body() }

    /** Ping and traceroute run on the switch; read-only, so no confirmation. */
    suspend fun ping(s: ServerSettings, deviceId: String, target: String, count: Int): DiagnosticDto =
        post(s, "devices/$deviceId/ping", DiagnosticBody(target, count))

    suspend fun traceroute(s: ServerSettings, deviceId: String, target: String): DiagnosticDto =
        post(s, "devices/$deviceId/traceroute", DiagnosticBody(target))

    private suspend inline fun <reified B : Any, reified T> post(s: ServerSettings, path: String, body: B): T = guarded {
        client.post("${requireBase(s)}/api/v1/$path") {
            auth(s)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    /** Turns the server's `{"detail": {"code": ...}}` refusals into messages that say what happened. */
    private suspend inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ResponseException) {
        val status = e.response.status.value
        if (status == 401) throw e
        val code = runCatching { json.decodeFromString(ErrorDto.serializer(), e.response.bodyAsText()).detail?.code }.getOrNull()
        throw UserFacingException(writeErrorMessage(code, status), e)
    }

    private fun requireBase(s: ServerSettings): String {
        if (s.baseUrl.isBlank()) {
            throw UserFacingException("Укажите адрес сервера в настройках или включите демо-данные.")
        }
        return s.baseUrl
    }

    private fun HttpRequestBuilder.auth(s: ServerSettings) {
        if (s.apiToken.isNotBlank()) bearerAuth(s.apiToken)
    }

    private suspend inline fun <reified T> fetch(s: ServerSettings, path: String): T {
        return client.get("${requireBase(s)}/api/v1/$path") { auth(s) }.body()
    }
}
