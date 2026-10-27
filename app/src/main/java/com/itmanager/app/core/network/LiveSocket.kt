package com.itmanager.app.core.network

import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.data.model.LiveEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * The server's live channel (`/api/v1/ws`). Reconnects on its own with growing pauses,
 * so a phone walking out of Wi-Fi range and back picks the stream up again without user action.
 */
@Singleton
class LiveSocket @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {
    fun events(settings: ServerSettings, deviceId: String): Flow<LiveEvent> = channelFlow {
        // No server yet: nothing to listen to until one is saved in the settings.
        if (!settings.isConfigured) return@channelFlow
        var attempt = 0
        while (true) {
            try {
                client.webSocket(urlString = socketUrl(settings), request = {
                    // The plain request timeouts would cut a long-lived socket.
                    timeout {
                        requestTimeoutMillis = Long.MAX_VALUE
                        socketTimeoutMillis = Long.MAX_VALUE
                    }
                }) {
                    send(buildJsonObject { put("action", "subscribe"); put("device_id", deviceId) }.toString())
                    attempt = 0
                    this@channelFlow.send(LiveEvent.Connected)
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        parse(frame.readText())?.let { this@channelFlow.send(it) }
                    }
                }
                send(LiveEvent.Disconnected("Сервер закрыл соединение"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                send(LiveEvent.Disconnected(e.toUserMessage()))
            }
            attempt++
            delay(min(30_000L, 1_000L shl min(attempt, 5)))
        }
    }

    private fun socketUrl(s: ServerSettings): String {
        val ws = when {
            s.baseUrl.startsWith("https://") -> "wss://" + s.baseUrl.removePrefix("https://")
            else -> "ws://" + s.baseUrl.removePrefix("http://")
        }
        val token = if (s.apiToken.isBlank()) "" else "?token=" + s.apiToken.encodeURLParameter()
        return "$ws/api/v1/ws$token"
    }

    private fun parse(text: String): LiveEvent? = runCatching { parseOrThrow(text) }.getOrNull()

    private fun parseOrThrow(text: String): LiveEvent? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val deviceId = obj.string("device_id")
        return when (obj.string("type")) {
            "snapshot" -> LiveEvent.Snapshot(
                deviceId = deviceId ?: return null,
                isOnline = obj["is_online"]?.jsonPrimitive?.booleanOrNull,
                ports = runCatching {
                    json.decodeFromJsonElement(ListSerializer(PortDto.serializer()), obj["ports"]!!)
                }.getOrNull()?.map { it.toDomain() } ?: return null,
            )
            "port_status" -> LiveEvent.PortChanged(
                deviceId = deviceId ?: return null,
                port = runCatching { json.decodeFromJsonElement(PortDto.serializer(), obj["port"]!!) }.getOrNull()?.toDomain()
                    ?: return null,
            )
            "device_status" -> LiveEvent.DeviceStatus(
                deviceId = deviceId ?: return null,
                isOnline = obj["is_online"]?.jsonPrimitive?.booleanOrNull ?: return null,
            )
            "alert" -> LiveEvent.AlertRaised(
                runCatching { json.decodeFromJsonElement(AlertDto.serializer(), obj["alert"]!!) }.getOrNull()?.toDomain()
                    ?: return null,
            )
            "printer_status" -> LiveEvent.PrinterChanged(
                runCatching { json.decodeFromJsonElement(PrinterDto.serializer(), obj["printer"]!!) }.getOrNull()?.toDomain()
                    ?: return null,
            )
            "inventory_changed" -> LiveEvent.InventoryChanged
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}
