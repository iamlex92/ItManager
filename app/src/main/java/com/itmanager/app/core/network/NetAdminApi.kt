package com.itmanager.app.core.network

import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.PortChange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

    /**
     * A floor plan file: the bundled SVG from /static, or a plan uploaded from the app. The token goes
     * only to the configured server, never to another host a plan URL might name.
     */
    suspend fun planBytes(s: ServerSettings, url: String): ByteArray = guarded {
        client.get(url) { if (s.baseUrl.isNotBlank() && url.startsWith(s.baseUrl + "/")) auth(s) }.body()
    }

    // Map editor. Inventory only: none of these reach a device.

    suspend fun createFloor(s: ServerSettings, body: FloorCreateBody): FloorDto = post(s, "floors", body)

    suspend fun patchFloor(s: ServerSettings, id: String, body: FloorPatch): FloorDto = patch(s, "floors/$id", body)

    suspend fun renameFloor(s: ServerSettings, id: String, body: FloorRename): FloorDto = patch(s, "floors/$id", body)

    suspend fun deleteFloor(s: ServerSettings, id: String) {
        guarded { client.delete("${requireBase(s)}/api/v1/floors/$id") { auth(s) } }
    }

    suspend fun patchConnection(s: ServerSettings, id: String, body: JsonObject): ConnectionDto =
        patch(s, "connections/$id", body)

    suspend fun createPort(s: ServerSettings, body: JsonObject): PortDto = post(s, "ports", body)

    suspend fun patchPort(s: ServerSettings, id: String, body: JsonObject): PortDto = patch(s, "ports/$id", body)

    suspend fun deletePort(s: ServerSettings, id: String) {
        guarded { client.delete("${requireBase(s)}/api/v1/ports/$id") { auth(s) } }
    }

    suspend fun routeConnection(s: ServerSettings, id: String, body: RouteBody): ConnectionDto =
        patch(s, "connections/$id", body)

    suspend fun uploadPlan(s: ServerSettings, floorId: String, bytes: ByteArray, mime: String, width: Int, height: Int): FloorDto = guarded {
        client.put("${requireBase(s)}/api/v1/floors/$floorId/plan-image") {
            auth(s)
            parameter("width", width)
            parameter("height", height)
            contentType(ContentType.parse(mime))
            setBody(bytes)
        }.body()
    }

    suspend fun deletePlan(s: ServerSettings, floorId: String): FloorDto =
        guarded { client.delete("${requireBase(s)}/api/v1/floors/$floorId/plan-image") { auth(s) }.body() }

    suspend fun createDevice(s: ServerSettings, body: DeviceBody): DeviceDto = post(s, "devices", body)

    suspend fun moveDevice(s: ServerSettings, id: String, body: DeviceMove): DeviceDto = patch(s, "devices/$id", body)

    suspend fun patchDevice(s: ServerSettings, id: String, body: JsonObject): DeviceDto = patch(s, "devices/$id", body)

    suspend fun bulkUpdateDevices(s: ServerSettings, body: BulkUpdateBody): BulkResultDto = post(s, "devices/bulk", body)

    suspend fun bulkDeleteDevices(s: ServerSettings, body: BulkDeleteBody): BulkResultDto =
        post(s, "devices/bulk-delete", body)

    suspend fun deleteDevice(s: ServerSettings, id: String) {
        guarded { client.delete("${requireBase(s)}/api/v1/devices/$id") { auth(s) } }
    }

    suspend fun linkDevices(s: ServerSettings, body: LinkBody): ConnectionDto = post(s, "connections/link", body)

    suspend fun deleteConnection(s: ServerSettings, id: String) {
        guarded { client.delete("${requireBase(s)}/api/v1/connections/$id") { auth(s) } }
    }

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

    /** Reads pasted config text into the inventory only; nothing is sent to the switch. */
    suspend fun importConfig(s: ServerSettings, deviceId: String, text: String, apply: Boolean): ConfigImportDto =
        post(s, "devices/$deviceId/config-import", ConfigImportBody(text, apply))

    /** Ping and traceroute run on the switch; read-only, so no confirmation. */
    suspend fun ping(s: ServerSettings, deviceId: String, target: String, count: Int): DiagnosticDto =
        post(s, "devices/$deviceId/ping", DiagnosticBody(target, count))

    suspend fun traceroute(s: ServerSettings, deviceId: String, target: String): DiagnosticDto =
        post(s, "devices/$deviceId/traceroute", DiagnosticBody(target))

    suspend fun deviceByNfc(s: ServerSettings, uid: String): DeviceDto = try {
        fetch(s, "devices/by-nfc/${uid.encodeURLPathPart()}")
    } catch (e: ResponseException) {
        if (e.response.status.value == 404) throw UnknownTagException() else throw e
    }

    /** One device, or [UnknownTagException] when the id from a tag no longer exists. */
    suspend fun deviceForTag(s: ServerSettings, id: String): DeviceDto = try {
        device(s, id)
    } catch (e: ResponseException) {
        if (e.response.status.value == 404 || e.response.status.value == 422) throw UnknownTagException() else throw e
    }

    /**
     * Binds a tag in the server's inventory (database only, the device is not touched).
     * A tag already bound elsewhere is refused unless [force] moves it.
     */
    suspend fun bindNfc(s: ServerSettings, deviceId: String, uid: String, force: Boolean): DeviceDto = try {
        client.put("${requireBase(s)}/api/v1/devices/$deviceId/nfc") {
            auth(s)
            contentType(ContentType.Application.Json)
            setBody(NfcBindBody(uid, force))
        }.body()
    } catch (e: ResponseException) {
        val status = e.response.status.value
        if (status == 401) throw e
        val detail = runCatching { json.decodeFromString(ErrorDto.serializer(), e.response.bodyAsText()).detail }.getOrNull()
        throw when {
            detail?.code == "tag_bound_elsewhere" -> TagBoundElsewhereException(detail.deviceName)
            detail?.code == "not_found" -> UserFacingException("Устройство не найдено на сервере.", e)
            status == 404 || status == 405 -> UserFacingException("Сервер пока не умеет привязывать метки: обновите сервер.", e)
            else -> UserFacingException("Сервер не привязал метку (код $status).", e)
        }
    }

    suspend fun alerts(s: ServerSettings): List<AlertDto> = fetch(s, "alerts")

    /** Marks an incident handled in the server's journal; nothing on the equipment changes. */
    suspend fun resolveAlert(s: ServerSettings, alertId: String): AlertDto =
        client.post("${requireBase(s)}/api/v1/alerts/$alertId/resolve") { auth(s) }.body()

    /** Asks the server to read the printer over SNMP now instead of waiting for the next poll. */
    suspend fun refreshPrinter(s: ServerSettings, printerId: String): PrinterDto =
        client.post("${requireBase(s)}/api/v1/printers/$printerId/refresh") { auth(s) }.body()

    suspend fun runbooks(s: ServerSettings): List<RunbookDto> = fetch(s, "runbooks")

    /** Runs a runbook; critical ones carry confirm = true, sent only after biometric confirmation. */
    suspend fun runRunbook(s: ServerSettings, id: String, body: RunbookRunBody): RunbookRunDto =
        post(s, "runbooks/${id.encodeURLPathPart()}/run", body)

    suspend fun audit(s: ServerSettings, kind: AuditKind?): List<AuditDto> =
        fetch(s, "audit?limit=200" + kind?.let { "&kind=${it.wire}" }.orEmpty())

    /** Starts a read-only scan of the given ranges; the server answers at once and the scan runs in the background. */
    suspend fun startScan(s: ServerSettings, body: ScanBody): ScanDto = discovery { post(s, "discovery/scans", body) }

    suspend fun scan(s: ServerSettings, id: String): ScanDto =
        discovery { guarded { fetch(s, "discovery/scans/${id.encodeURLPathPart()}") } }

    /** The last scan the server remembers, or null when there was none since it started. */
    suspend fun latestScan(s: ServerSettings): ScanDto? = try {
        fetch<ScanDto>(s, "discovery/scans/latest")
    } catch (e: ResponseException) {
        if (e.response.status.value == 404) null else throw e
    }

    /** Adds chosen devices and their links to the server's inventory (database only). */
    suspend fun importScan(s: ServerSettings, id: String, body: ImportBody): ImportDto =
        discovery { post(s, "discovery/scans/${id.encodeURLPathPart()}/import", body) }

    /** Port writes, config saves and stored config copies of one switch, newest first. */
    suspend fun deviceHistory(s: ServerSettings, deviceId: String): List<HistoryEntryDto> = try {
        fetch(s, "devices/${deviceId.encodeURLPathPart()}/history?limit=200")
    } catch (e: ResponseException) {
        if (e.response.status.value != 404) throw e
        val code = runCatching { json.decodeFromString(ErrorDto.serializer(), e.response.bodyAsText()).detail?.code }.getOrNull()
        throw UserFacingException(
            if (code == null) "Сервер пока не ведёт историю изменений: обновите сервер." else "Устройство не найдено на сервере.",
            e,
        )
    }

    suspend fun switchTraffic(s: ServerSettings, deviceId: String, hours: Int): DeviceTrafficDto =
        trafficCall { fetch<DeviceTrafficDto>(s, "devices/${deviceId.encodeURLPathPart()}/traffic?hours=$hours") }

    suspend fun portTraffic(s: ServerSettings, portId: String, hours: Int, points: Int): PortTrafficDto =
        trafficCall { fetch<PortTrafficDto>(s, "ports/${portId.encodeURLPathPart()}/traffic?hours=$hours&points=$points") }

    /** A server from before traffic sampling answers a plain 404 without our error code. */
    private suspend inline fun <T> trafficCall(block: () -> T): T = try {
        block()
    } catch (e: ResponseException) {
        if (e.response.status.value != 404) throw e
        val code = runCatching { json.decodeFromString(ErrorDto.serializer(), e.response.bodyAsText()).detail?.code }.getOrNull()
        throw UserFacingException(
            if (code == null) "Сервер пока не собирает загрузку портов: обновите сервер." else "Порт не найден на сервере.",
            e,
        )
    }

    /** Stored logins without their secrets. */
    suspend fun credentials(s: ServerSettings): List<CredentialDto> = credentialCall { guarded { fetch<List<CredentialDto>>(s, "credentials") } }

    suspend fun createCredential(s: ServerSettings, body: CredentialBody): CredentialDto =
        credentialCall { post(s, "credentials", body) }

    suspend fun updateCredential(s: ServerSettings, id: String, body: CredentialPatch): CredentialDto = credentialCall {
        guarded {
            client.patch("${requireBase(s)}/api/v1/credentials/${id.encodeURLPathPart()}") {
                auth(s)
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        }
    }

    suspend fun deleteCredential(s: ServerSettings, id: String, operator: String) {
        credentialCall {
            guarded {
                client.delete("${requireBase(s)}/api/v1/credentials/${id.encodeURLPathPart()}") {
                    auth(s)
                    parameter("operator", operator)
                }
            }
        }
    }

    /** A server from before credential storage answers 404 or 405 on the list. */
    private suspend inline fun <T> credentialCall(block: () -> T): T = try {
        block()
    } catch (e: UserFacingException) {
        val response = (e.cause as? ResponseException)?.response ?: throw e
        val code = runCatching { json.decodeFromString(ErrorDto.serializer(), response.bodyAsText()).detail?.code }.getOrNull()
        throw when {
            code == "not_found" -> UserFacingException("Этих учётных данных уже нет на сервере. Обновите список.", e)
            response.status.value == 404 || response.status.value == 405 ->
                UserFacingException("Сервер пока не умеет хранить учётные данные: обновите сервер.", e)
            else -> e
        }
    }

    /** A server from before discovery answers 404 or 405 on these paths; a restarted one has forgotten its scans. */
    private suspend inline fun <T> discovery(block: () -> T): T = try {
        block()
    } catch (e: UserFacingException) {
        val response = (e.cause as? ResponseException)?.response ?: throw e
        val code = runCatching { json.decodeFromString(ErrorDto.serializer(), response.bodyAsText()).detail?.code }.getOrNull()
        throw when {
            code == "not_found" -> UserFacingException("Сервер не помнит это сканирование (возможно, он перезапускался). Запустите его заново.", e)
            response.status.value == 404 || response.status.value == 405 ->
                UserFacingException("Сервер пока не умеет сканировать сеть: обновите сервер.", e)
            else -> e
        }
    }

    private suspend inline fun <reified B : Any, reified T> patch(s: ServerSettings, path: String, body: B): T = guarded {
        client.patch("${requireBase(s)}/api/v1/$path") {
            auth(s)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    private suspend inline fun <reified B : Any, reified T> post(s: ServerSettings, path: String, body: B): T = guarded {
        client.post("${requireBase(s)}/api/v1/$path") {
            auth(s)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    /** A server from before group edits answers 404 or 405 on their paths. */
    fun isMissingEndpoint(e: Throwable): Boolean {
        val status = ((e as? UserFacingException)?.cause as? ResponseException)?.response?.status?.value
        return status == 404 || status == 405
    }

    /** Turns the server's `{"detail": {"code": ...}}` refusals into messages that say what happened. */
    private suspend inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ResponseException) {
        val status = e.response.status.value
        if (status == 401) throw e
        val detail = runCatching { json.decodeFromString(ErrorDto.serializer(), e.response.bodyAsText()).detail }.getOrNull()
        val message = detail?.message
        throw UserFacingException(
            // Argument errors come back already phrased in Russian for the form.
            when {
                detail?.code == "invalid_params" && message != null -> "Проверьте поля: $message."
                detail?.code in PHRASED_CODES && message != null -> message.replaceFirstChar { it.uppercase() } + "."
                else -> writeErrorMessage(detail?.code, status)
            },
            e,
        )
    }

    private companion object {
        /** Refusals whose message the server already writes in Russian for the user. */
        val PHRASED_CODES = setOf(
            "invalid_subnets", "scan_running", "name_taken",
            "already_linked", "no_free_port", "same_device", "plan_type", "plan_too_big", "empty_plan",
        )
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
