package com.itmanager.app.core.network

import com.itmanager.app.core.settings.ServerSettings
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which server the app talks to: the main one, or the backup while the main one cannot be reached. Only a failure to
 * connect at all moves it over, so a request the main server may have received is never sent twice. On the backup,
 * the main server is tried first again every [RETRY_MAIN_MS] and taken back as soon as it answers.
 */
@Singleton
class ServerFailover(private val clock: () -> Long) {
    @Inject constructor() : this(System::currentTimeMillis)

    private val _onBackup = MutableStateFlow(false)
    /** The app works through the backup server now. */
    val onBackup: StateFlow<Boolean> = _onBackup.asStateFlow()

    private var backupSince = 0L

    /** The servers to try for one request, in order. */
    fun order(settings: ServerSettings): List<ServerSettings> {
        val backup = settings.backup() ?: run {
            _onBackup.value = false
            return listOf(settings)
        }
        return if (_onBackup.value && clock() - backupSince < RETRY_MAIN_MS) listOf(backup, settings) else listOf(settings, backup)
    }

    /** The server a live stream should open on: the one requests go to now. */
    fun active(settings: ServerSettings): ServerSettings =
        settings.backup()?.takeIf { _onBackup.value } ?: settings

    /** «Вернуться на основной сейчас»: the next request tries the main server first, whatever the minute says. */
    fun tryMainNow() {
        backupSince = Long.MIN_VALUE / 2
    }

    /** [used] answered: it is the server from now on. */
    fun answered(settings: ServerSettings, used: ServerSettings) {
        val backup = settings.backup() != null && used.baseUrl != settings.baseUrl
        if (backup) backupSince = clock()
        _onBackup.value = backup
    }

    /**
     * Runs [block] on the main server, and on the backup when the main one cannot be reached (or the other way round
     * while on the backup). Any other failure, an error from a server that answered included, is returned as it is.
     */
    suspend fun <T> run(settings: ServerSettings, block: suspend (ServerSettings) -> T): Result<T> {
        var last: Result<T>? = null
        for (server in order(settings)) {
            val result = runCatchingCancellable { block(server) }
            if (result.isSuccess) {
                answered(settings, server)
                return result
            }
            last = result
            if (!unreachable(result.exceptionOrNull())) return result
        }
        return last ?: Result.failure(IllegalStateException("no server"))
    }

    companion object {
        const val RETRY_MAIN_MS = 60_000L

        /** The request never reached a server: nothing listens, no route, no such name, no connection in time. */
        fun unreachable(e: Throwable?): Boolean {
            var t = e
            while (t != null) {
                if (t is ConnectException || t is UnknownHostException || t is NoRouteToHostException || t is ConnectTimeoutException) {
                    return true
                }
                t = t.cause?.takeIf { it !== t }
            }
            return false
        }
    }
}
