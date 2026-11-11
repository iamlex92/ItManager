package com.itmanager.app.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks before a change is sent to a backup server that accepts changes: the next copy from the main server replaces
 * them. The screen shows the question ([pending]) and answers it; a yes holds for [HOLD_MS], so a series of edits does
 * not ask each time.
 */
@Singleton
class BackupWriteGate(private val clock: () -> Long) {
    @Inject constructor() : this(System::currentTimeMillis)

    private val _pending = MutableStateFlow<CompletableDeferred<Boolean>?>(null)
    val pending: StateFlow<CompletableDeferred<Boolean>?> = _pending.asStateFlow()

    private var allowedUntil = 0L

    suspend fun confirm(): Boolean {
        if (clock() < allowedUntil) return true
        val answer = CompletableDeferred<Boolean>()
        _pending.value = answer
        val ok = try {
            answer.await()
        } finally {
            _pending.value = null
        }
        if (ok) allowedUntil = clock() + HOLD_MS
        return ok
    }

    /** Back on the main server: the next backup session asks again. */
    fun forget() {
        allowedUntil = 0L
    }

    companion object {
        const val HOLD_MS = 5 * 60_000L
    }
}
