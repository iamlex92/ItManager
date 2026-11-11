package com.itmanager.app

import com.itmanager.app.core.network.ServerFailover
import com.itmanager.app.core.settings.ServerSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class ServerFailoverTest {
    private val settings = ServerSettings("http://main:8000", "t-main", backupUrl = "http://spare:8000")
    private var now = 0L
    private val failover = ServerFailover { now }

    @Test
    fun movesToTheBackupWhenTheMainServerCannotBeReachedAndBackLater() = runBlocking {
        val asked = mutableListOf<String>()
        var mainUp = false
        val call: suspend (ServerSettings) -> String = { s ->
            asked += s.baseUrl
            if (s.baseUrl == "http://main:8000" && !mainUp) throw ConnectException("refused")
            s.apiToken
        }
        // The backup has no token of its own: the main one goes with it.
        assertEquals("t-main", failover.run(settings, call).getOrThrow())
        assertTrue(failover.onBackup.value)
        assertEquals(listOf("http://main:8000", "http://spare:8000"), asked)

        // Straight to the backup for a minute.
        asked.clear()
        failover.run(settings, call)
        assertEquals(listOf("http://spare:8000"), asked)

        // Then the main server first again; it answers, and it is the server again.
        now += ServerFailover.RETRY_MAIN_MS
        mainUp = true
        asked.clear()
        failover.run(settings, call)
        assertEquals(listOf("http://main:8000"), asked)
        assertFalse(failover.onBackup.value)
    }

    @Test
    fun anAnswerFromTheMainServerIsNeverRetriedOnTheBackup() = runBlocking {
        val asked = mutableListOf<String>()
        val r = failover.run(settings) { s -> asked += s.baseUrl; error("422 from the server") }
        assertTrue(r.isFailure)
        assertEquals(listOf("http://main:8000"), asked)
        assertFalse(failover.onBackup.value)
    }

    @Test
    fun withoutABackupOnlyTheMainServer() = runBlocking {
        val asked = mutableListOf<String>()
        val r = failover.run(ServerSettings("http://main:8000")) { s -> asked += s.baseUrl; throw ConnectException() }
        assertTrue(r.isFailure && ServerFailover.unreachable(r.exceptionOrNull()))
        assertEquals(listOf("http://main:8000"), asked)
    }

    @Test
    fun tryMainNowSkipsTheMinuteOnTheBackup() = runBlocking {
        now = 1_700_000_000_000L
        val asked = mutableListOf<String>()
        var mainUp = false
        val call: suspend (ServerSettings) -> String = { s ->
            asked += s.baseUrl
            if (s.baseUrl == "http://main:8000" && !mainUp) throw ConnectException("refused")
            s.apiToken
        }
        failover.run(settings, call)
        assertTrue(failover.onBackup.value)
        mainUp = true
        asked.clear()
        failover.tryMainNow()
        failover.run(settings, call)
        assertEquals(listOf("http://main:8000"), asked)
        assertFalse(failover.onBackup.value)
    }
}
