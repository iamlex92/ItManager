package com.itmanager.app.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.ConnectException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The last good answer to the read requests that draw the map and the lists, kept on the phone. While no server can be
 * reached the repository switches [serving] on and the same requests are answered from here, so the app shows the
 * last known state (marked with its time, with changes off) instead of an error on every screen.
 *
 * Only GETs of the inventory are kept; passwords, scripts, journals and everything else never touch the disk.
 */
class OfflineCache(private val dir: File) {
    private val serving = AtomicBoolean(false)
    private val oldestServed = AtomicLong(0)

    /** Answers are taken from the cache while on. */
    fun serve(on: Boolean) {
        serving.set(on)
        if (on) oldestServed.set(0)
    }

    /** When the oldest answer served since [serve] was switched on was saved (ms), or 0 if none was. */
    fun servedFrom(): Long = oldestServed.get()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        if (request.method != "GET" || !cacheable(url.encodedPath)) return@Interceptor chain.proceed(request)
        val key = key(url.toString())
        if (serving.get()) {
            val file = File(dir, key)
            if (!file.isFile) throw ConnectException("нет связи с сервером, сохранённых данных нет")
            val saved = file.lastModified()
            oldestServed.updateAndGet { if (it == 0L || saved < it) saved else it }
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK (saved copy)")
                .header("X-NetAdmin-Saved-At", saved.toString())
                .body(file.readBytes().toResponseBody("application/json".toMediaType()))
                .build()
        }
        val response = chain.proceed(request)
        if (!response.isSuccessful) return@Interceptor response
        val body = response.body
        val bytes = body.bytes()
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(dir, key))
        }
        response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
    }

    companion object {
        private val ALLOWED = listOf("floors", "devices", "vlans", "connections", "alerts", "printers", "peripherals", "topology")

        /** Inventory reads only: /api/v1/<first segment> is one of the lists that draw the screens. */
        fun cacheable(path: String): Boolean {
            val parts = path.trim('/').split('/')
            // The bundled floor plans are static pictures.
            if (parts.firstOrNull() == "static" && parts.last().substringAfterLast('.', "") in setOf("svg", "png", "jpg", "jpeg")) return true
            return parts.size >= 3 && parts[0] == "api" && parts[1] == "v1" && parts[2] in ALLOWED
        }

        private fun key(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
