package com.itmanager.app.core.nettools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

data class Iperf3Progress(val second: Int, val mbps: Double)

data class Iperf3Result(
    val reverse: Boolean,
    val seconds: Double,
    /** Throughput seen by this phone. */
    val localBytes: Long,
    /** Bytes the server reports for its side of the same test, or null if it sent no usable results. */
    val remoteBytes: Long?,
    val intervals: List<Double>,
) {
    val localMbps: Double get() = if (seconds > 0) localBytes * 8 / seconds / 1e6 else 0.0
    val remoteMbps: Double? get() = remoteBytes?.let { if (seconds > 0) it * 8 / seconds / 1e6 else 0.0 }

    /** What the receiver measured: the figure that matters. Upload: the server; download: this phone. */
    val receiverMbps: Double get() = if (reverse) localMbps else (remoteMbps ?: localMbps)
    val senderMbps: Double get() = if (reverse) (remoteMbps ?: localMbps) else localMbps
}

class Iperf3Exception(message: String) : IOException(message)

/**
 * iperf3 TCP client: control channel on the server port, then one data connection per stream.
 * A plain `iperf3 -s` on the other side is enough.
 */
class Iperf3Client(
    private val host: String,
    private val port: Int = 5201,
    private val seconds: Int = 5,
    private val streams: Int = 1,
    private val reverse: Boolean = false,
    private val blockSize: Int = 128 * 1024,
) {
    companion object {
        const val TEST_START = 1
        const val TEST_RUNNING = 2
        const val TEST_END = 4
        const val PARAM_EXCHANGE = 9
        const val CREATE_STREAMS = 10
        const val SERVER_TERMINATE = 11
        const val EXCHANGE_RESULTS = 13
        const val DISPLAY_RESULTS = 14
        const val IPERF_DONE = 16
        const val ACCESS_DENIED = -1
        const val SERVER_ERROR = -2
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        fun newCookie(): ByteArray {
            val r = SecureRandom()
            return ByteArray(37) { i -> if (i == 36) 0 else ALPHABET[r.nextInt(ALPHABET.length)].code.toByte() }
        }

        fun paramsJson(seconds: Int, streams: Int, reverse: Boolean, blockSize: Int): String =
            buildString {
                append("{\"tcp\":true,\"omit\":0,\"time\":").append(seconds)
                append(",\"parallel\":").append(streams)
                append(",\"len\":").append(blockSize)
                if (reverse) append(",\"reverse\":true")
                append(",\"client_version\":\"3.9\"}")
            }

        fun resultsJson(bytesPerStream: List<Long>, seconds: Double, sender: Boolean): String =
            buildString {
                append("{\"cpu_util_total\":0,\"cpu_util_user\":0,\"cpu_util_system\":0,\"sender_has_retransmits\":-1,\"streams\":[")
                bytesPerStream.forEachIndexed { i, b ->
                    if (i > 0) append(',')
                    append("{\"id\":").append(i + 1).append(",\"bytes\":").append(b)
                    append(",\"retransmits\":-1")
                    append(",\"jitter\":0,\"errors\":0,\"packets\":0,\"start_time\":0,\"end_time\":").append("%.6f".format(java.util.Locale.US, seconds)).append('}')
                }
                append("]}")
            }

        /** Sum of "bytes" over the streams of a results object, or null if the JSON is not one. */
        fun totalBytes(json: String): Long? = try {
            (Json.parseToJsonElement(json) as JsonObject)["streams"]!!.jsonArray.sumOf { it.jsonObject["bytes"]!!.jsonPrimitive.longOrNull ?: 0L }
        } catch (e: Exception) {
            null
        }
    }

    @Volatile private var aborted = false
    private val sockets = java.util.Collections.synchronizedList(ArrayList<Socket>())

    fun abort() {
        aborted = true
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    private fun connect(): Socket {
        val s = Socket()
        sockets += s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5000)
        return s
    }

    private fun DataInputStream.state(): Int {
        val b = read()
        if (b < 0) throw EOFException("Сервер закрыл соединение")
        return b.toByte().toInt()
    }

    private fun sendJson(out: java.io.OutputStream, json: String) {
        val data = json.toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(data); out.flush()
    }

    private fun readJson(inp: DataInputStream): String {
        val len = inp.readInt()
        if (len < 0 || len > 1_000_000) throw Iperf3Exception("Неожиданный ответ сервера")
        val buf = ByteArray(len)
        inp.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    /** Blocks for about [seconds] plus the handshake; [onProgress] is called once per second. */
    fun run(onProgress: (Iperf3Progress) -> Unit = {}): Iperf3Result {
        val cookie = newCookie()
        val data = ArrayList<Socket>()
        val counters = java.util.concurrent.atomic.AtomicLongArray(streams)
        val workers = ArrayList<Thread>()
        val intervals = ArrayList<Double>()
        var elapsed = 0.0
        var remoteBytes: Long? = null
        try {
            val control = connect().also { it.soTimeout = 15_000 }
            val cin = DataInputStream(control.getInputStream())
            val cout = control.getOutputStream()
            cout.write(cookie); cout.flush()
            while (true) {
                if (aborted) throw Iperf3Exception("Остановлено")
                when (val st = cin.state()) {
                    PARAM_EXCHANGE -> sendJson(cout, paramsJson(seconds, streams, reverse, blockSize))
                    CREATE_STREAMS -> repeat(streams) {
                        val s = connect()
                        s.getOutputStream().apply { write(cookie); flush() }
                        data += s
                    }
                    TEST_START -> {}
                    TEST_RUNNING -> {
                        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
                        data.forEachIndexed { i, s ->
                            workers += Thread {
                                val buf = ByteArray(blockSize)
                                try {
                                    if (reverse) {
                                        val inp = s.getInputStream()
                                        while (!stop.get()) { val n = inp.read(buf); if (n < 0) break; counters.addAndGet(i, n.toLong()) }
                                    } else {
                                        val out = s.getOutputStream()
                                        while (!stop.get()) { out.write(buf); counters.addAndGet(i, buf.size.toLong()) }
                                    }
                                } catch (_: IOException) {
                                }
                            }.apply { isDaemon = true; start() }
                        }
                        val t0 = System.nanoTime()
                        var last = 0L
                        for (sec in 1..seconds) {
                            val target = t0 + sec * 1_000_000_000L
                            while (System.nanoTime() < target) { if (aborted) throw Iperf3Exception("Остановлено"); Thread.sleep(20) }
                            val total = (0 until streams).sumOf { counters.get(it) }
                            val mbps = (total - last) * 8 / 1e6
                            last = total
                            intervals += mbps
                            onProgress(Iperf3Progress(sec, mbps))
                        }
                        elapsed = (System.nanoTime() - t0) / 1e9
                        stop.set(true)
                        cout.write(TEST_END); cout.flush()
                        data.forEach { runCatching { it.shutdownOutput() } }
                    }
                    EXCHANGE_RESULTS -> {
                        sendJson(cout, resultsJson((0 until streams).map { counters.get(it) }, elapsed, sender = !reverse))
                        remoteBytes = totalBytes(readJson(cin))
                    }
                    DISPLAY_RESULTS -> { cout.write(IPERF_DONE); cout.flush(); break }
                    ACCESS_DENIED -> throw Iperf3Exception("Сервер отказал в тесте (идёт другой тест?)")
                    SERVER_ERROR -> throw Iperf3Exception("Сервер сообщил об ошибке")
                    SERVER_TERMINATE -> throw Iperf3Exception("Сервер завершил тест")
                    else -> throw Iperf3Exception("Неожиданное состояние $st: это точно сервер iperf3?")
                }
            }
            return Iperf3Result(reverse, elapsed, (0 until streams).sumOf { counters.get(it) }, remoteBytes, intervals)
        } catch (e: java.net.SocketTimeoutException) {
            throw Iperf3Exception("Сервер не ответил вовремя")
        } catch (e: java.net.ConnectException) {
            throw Iperf3Exception("Не удаётся подключиться к $host:$port")
        } catch (e: IOException) {
            if (aborted) throw Iperf3Exception("Остановлено")
            throw e
        } finally {
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            workers.forEach { it.join(500) }
        }
    }
}
