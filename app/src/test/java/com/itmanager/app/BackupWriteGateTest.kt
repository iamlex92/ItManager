package com.itmanager.app

import com.itmanager.app.core.network.BackupWriteGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupWriteGateTest {
    private var now = 0L
    private val gate = BackupWriteGate { now }

    private suspend fun answer(yes: Boolean) {
        var q: CompletableDeferred<Boolean>? = null
        while (q == null) {
            q = gate.pending.value
            yield()
        }
        q.complete(yes)
    }

    @Test
    fun asksOnceAndHoldsTheAnswerForAWhile() = runBlocking {
        val first = async { gate.confirm() }
        answer(true)
        assertTrue(first.await())
        assertNull(gate.pending.value)
        // Within the hold: no question.
        assertTrue(gate.confirm())
        // After it: asked again, and a no stays a no.
        now += BackupWriteGate.HOLD_MS
        val again = async { gate.confirm() }
        answer(false)
        assertFalse(again.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun backOnTheMainServerTheNextBackupSessionAsksAgain() = runBlocking {
        val first = async { gate.confirm() }
        answer(true)
        first.await()
        gate.forget()
        val next = async { gate.confirm() }
        answer(true)
        assertTrue(next.await())
        assertNotNull(gate)
    }
}
