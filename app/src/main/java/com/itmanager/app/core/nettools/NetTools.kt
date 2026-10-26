package com.itmanager.app.core.nettools

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class PingAttempt(val line: PingLine?, val wallMs: Double, val raw: String)
data class Hop(val ttl: Int, val addr: String?, val rttsMs: List<Double?>, val note: String?, val reached: Boolean)
data class DnsAnswer(val addresses: List<String>, val reverse: Map<String, String?>)

sealed interface PortEvent {
    data class Progress(val done: Int, val total: Int) : PortEvent
    data class Open(val port: Int) : PortEvent
}

data class HttpStep(val url: String, val code: Int, val ms: Long, val server: String?, val contentType: String?, val location: String?)
data class HttpProbe(val steps: List<HttpStep>, val error: String?, val tls: ServiceResult?)

/**
 * Tools that run on the phone itself, so they work where the server has no route: a cabinet, a workshop Wi-Fi,
 * a VPN. Everything here is read-only except Wake-on-LAN, which the user sends by hand.
 */
object NetTools {
    private const val TIMEOUT = 3000

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "nettools").apply { isDaemon = true } }

    /** Runs a blocking call with a hard timeout (blocking DNS calls cannot be cancelled otherwise). */
    private suspend fun <T> blocking(timeoutMs: Long, block: () -> T): T? = runInterruptible(Dispatchers.IO) {
        val f = pool.submit(Callable { block() })
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true); null
        } catch (e: java.util.concurrent.ExecutionException) {
            null
        }
    }

    /** Name or literal IP -> address (IPv4 preferred); null if it does not resolve. */
    suspend fun resolve(host: String): InetAddress? {
        val h = host.trim()
        if (h.isEmpty()) return null
        val all = blocking(4000) { InetAddress.getAllByName(h).toList() } ?: return null
        return all.firstOrNull { it !is Inet6Address } ?: all.firstOrNull()
    }

    suspend fun reverse(ip: String, timeoutMs: Long = 2500): String? =
        blocking(timeoutMs) { InetAddress.getByName(ip).canonicalHostName }?.takeIf { it != ip }

    suspend fun dns(query: String): DnsAnswer {
        val q = query.trim()
        val literal = Regex("^[0-9.]+$|^[0-9a-fA-F:]+$").matches(q) && q.any { it == '.' || it == ':' }
        val addresses = if (literal) listOf(q) else (blocking(4000) { InetAddress.getAllByName(q).mapNotNull { it.hostAddress } } ?: emptyList())
        val reverse = coroutineScope { addresses.map { a -> async { a to reverse(a) } }.awaitAll().toMap() }
        return DnsAnswer(addresses, reverse)
    }

    /** NetBIOS names of a Windows/Samba host (and its MAC), or null if it does not answer on UDP 137. */
    suspend fun netbios(ip: String, timeoutMs: Int = 1500): NbInfo? = runInterruptible(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val q = nbstatQuery()
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(1024)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                parseNbstat(buf, p.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- ping and traceroute through the system binary (unprivileged ICMP echo needs no root) ----

    /** One echo request with the given TTL. Falls back to InetAddress.isReachable if the binary is unavailable. */
    suspend fun pingOnce(addr: InetAddress, ttl: Int? = null, timeoutSec: Int = 2): PingAttempt = runInterruptible(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        fun wall() = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val bin = if (addr is Inet6Address) "/system/bin/ping6" else "/system/bin/ping"
        val cmd = ArrayList<String>().apply {
            add(bin); add("-c"); add("1"); add("-W"); add(timeoutSec.toString())
            if (ttl != null) { add("-t"); add(ttl.toString()) }
            add(addr.hostAddress ?: "")
        }
        var proc: Process? = null
        try {
            proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val parsed = text.lineSequence().mapNotNull(::parsePingLine).firstOrNull()
            PingAttempt(parsed, wall(), text)
        } catch (e: java.io.IOException) {
            if (ttl != null) {
                PingAttempt(null, wall(), "")
            } else {
                val ok = runCatching { addr.isReachable(timeoutSec * 1000) }.getOrDefault(false)
                val ms = wall()
                PingAttempt(if (ok) PingLine.Reply(addr.hostAddress ?: "", null, ms) else null, ms, "")
            }
        } finally {
            proc?.destroy()
        }
    }

    /** Emits one entry per probe: the round trip, or null on timeout. [count] <= 0 means "until cancelled". */
    fun ping(addr: InetAddress, count: Int, timeoutSec: Int = 2): Flow<Pair<Int, PingAttempt>> = flow {
        var seq = 1
        while (count <= 0 || seq <= count) {
            val started = SystemClock.elapsedRealtime()
            emit(seq to pingOnce(addr, null, timeoutSec))
            seq++
            val spent = SystemClock.elapsedRealtime() - started
            if (count <= 0 || seq <= count) delay((1000 - spent).coerceAtLeast(0))
        }
    }

    /** Hop by hop, three probes per TTL in parallel. Stops at the target, on "unreachable" or after silent hops. */
    fun traceroute(addr: InetAddress, maxHops: Int = 30, probes: Int = 3): Flow<Hop> = flow {
        var silent = 0
        for (ttl in 1..maxHops) {
            val attempts = coroutineScope { (1..probes).map { async { pingOnce(addr, ttl, 1) } }.awaitAll() }
            val rtts = attempts.map { a ->
                when (val l = a.line) {
                    is PingLine.Reply -> l.rttMs
                    is PingLine.Error -> if (l.ttlExceeded) a.wallMs else null
                    null -> null
                }
            }
            val lines = attempts.mapNotNull { it.line }
            val from = lines.firstNotNullOfOrNull { (it as? PingLine.Reply)?.from ?: (it as? PingLine.Error)?.from }
            val reached = lines.any { it is PingLine.Reply }
            val err = lines.filterIsInstance<PingLine.Error>().firstOrNull { !it.ttlExceeded }
            emit(Hop(ttl, from, rtts, err?.message, reached))
            if (reached || err != null) return@flow
            silent = if (from == null) silent + 1 else 0
            if (silent >= 6) return@flow
        }
    }

    // ---- TCP port scan ----

    fun portScan(addr: InetAddress, ports: List<Int>, timeoutMs: Int = 700, parallel: Int = 128): Flow<PortEvent> = channelFlow {
        val gate = Semaphore(parallel)
        var done = 0
        val total = ports.size
        val lock = Any()
        coroutineScope {
            ports.map { p ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val open = try {
                            Socket().use { it.connect(InetSocketAddress(addr, p), timeoutMs); true }
                        } catch (e: Exception) {
                            false
                        }
                        if (open) send(PortEvent.Open(p))
                        val d = synchronized(lock) { ++done }
                        if (d % 25 == 0 || d == total) send(PortEvent.Progress(d, total))
                    }
                }
            }.awaitAll()
        }
    }

    // ---- HTTP(S) and certificate ----

    private val trustAllContext: SSLContext by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    }

    /**
     * Reads the certificate without validating it, on purpose: the point is to report an expired or self-signed
     * certificate on a switch or printer, not to refuse to look at it. Nothing is sent over this connection.
     */
    fun tls(host: String, port: Int = 443): ServiceResult = try {
        (trustAllContext.socketFactory.createSocket() as SSLSocket).use { s ->
            s.soTimeout = TIMEOUT
            s.connect(InetSocketAddress(host, port), TIMEOUT)
            s.startHandshake()
            val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            if (cert == null) {
                ServiceResult("TLS", Severity.WARN, "нет сертификата")
            } else {
                val days = daysUntil(cert.notAfter.time, System.currentTimeMillis())
                val subject = cert.subjectX500Principal.name.substringAfter("CN=", cert.subjectX500Principal.name).substringBefore(',')
                val selfSigned = cert.subjectX500Principal == cert.issuerX500Principal
                val text = (if (days < 0) "истёк ${-days} дн. назад" else "действует ещё $days дн.") + " · $subject" + if (selfSigned) " · самоподписанный" else ""
                ServiceResult("TLS", certSeverity(days), text)
            }
        }
    } catch (e: Exception) {
        ServiceResult("TLS", Severity.FAIL, e.message ?: e.javaClass.simpleName)
    }

    /** Follows up to five redirects and times every step; the certificate comes from a separate handshake. */
    suspend fun http(input: String, verifyTls: Boolean): HttpProbe = runInterruptible(Dispatchers.IO) {
        val raw = input.trim()
        var url = if (raw.contains("://")) raw else "https://$raw"
        val steps = ArrayList<HttpStep>()
        var error: String? = null
        var triedHttp = raw.contains("://")
        var hops = 0
        while (hops < 6) {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val c = URL(url).openConnection() as HttpURLConnection
                if (c is HttpsURLConnection && !verifyTls) {
                    c.sslSocketFactory = trustAllContext.socketFactory
                    c.hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
                c.instanceFollowRedirects = false
                c.connectTimeout = 4000; c.readTimeout = 4000
                c.setRequestProperty("Connection", "close")
                c.setRequestProperty("User-Agent", "ItManager")
                try {
                    val code = c.responseCode
                    val ms = SystemClock.elapsedRealtime() - t0
                    val loc = c.getHeaderField("Location")
                    steps += HttpStep(url, code, ms, c.getHeaderField("Server"), c.contentType?.substringBefore(';'), loc)
                    if (code in 300..399 && loc != null) { url = URL(URL(url), loc).toString(); hops++; continue }
                    break
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                if (!triedHttp && steps.isEmpty()) { triedHttp = true; url = "http://$raw"; continue }
                error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                break
            }
        }
        val last = steps.lastOrNull()?.url ?: url
        val tls = if (last.startsWith("https")) runCatching {
            val u = URL(last)
            tls(u.host, if (u.port > 0) u.port else 443)
        }.getOrNull() else null
        HttpProbe(steps, error, tls)
    }

    // ---- Wake-on-LAN ----

    suspend fun wake(mac: ByteArray, broadcasts: List<String>, port: Int = 9): Int = runInterruptible(Dispatchers.IO) {
        val data = magicPacket(mac)
        var sent = 0
        DatagramSocket().use { s ->
            s.broadcast = true
            for (b in broadcasts.distinct()) {
                try {
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(b), port)); sent++
                } catch (_: Exception) {
                }
            }
        }
        sent
    }
}
