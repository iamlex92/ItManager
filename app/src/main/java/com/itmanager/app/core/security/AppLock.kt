package com.itmanager.app.core.security

import com.itmanager.app.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class LockState {
    /** The setting has not been read yet: nothing is shown, so the inventory never flashes before the lock. */
    CHECKING,
    LOCKED,
    UNLOCKED,
}

/**
 * Remembers when the app went to the background. A short trip out (the PIN screen of the system prompt,
 * a glance at a message) does not lock again; a longer one does.
 */
class LockTimer(private val timeoutMs: Long = RELOCK_AFTER_MS) {
    private var stoppedAt: Long? = null

    fun onBackground(nowMs: Long) {
        stoppedAt = nowMs
    }

    /** True once per return when the app was away for [timeoutMs] or longer. */
    fun shouldRelock(nowMs: Long): Boolean {
        val at = stoppedAt ?: return false
        stoppedAt = null
        return nowMs - at >= timeoutMs
    }

    companion object {
        const val RELOCK_AFTER_MS = 5 * 60_000L
    }
}

/**
 * Biometric login to the app. Locked on every cold start and after [LockTimer.RELOCK_AFTER_MS]
 * in the background, while the setting is on (the default).
 */
@Singleton
class AppLock @Inject constructor(settings: SettingsRepository) {
    private val _state = MutableStateFlow(LockState.CHECKING)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val timer = LockTimer()
    private var enabled = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            settings.appLock.collect { on ->
                enabled = on
                _state.update { current ->
                    when {
                        !on -> LockState.UNLOCKED
                        current == LockState.CHECKING -> LockState.LOCKED
                        // Turning the lock on from settings does not throw the operator out.
                        else -> current
                    }
                }
            }
        }
    }

    fun unlock() {
        _state.value = LockState.UNLOCKED
    }

    /** Elapsed-realtime clock, so changing the phone's time does not skip the lock. */
    fun onBackground(nowMs: Long) = timer.onBackground(nowMs)

    fun onForeground(nowMs: Long) {
        if (timer.shouldRelock(nowMs) && enabled) {
            _state.update { if (it == LockState.UNLOCKED) LockState.LOCKED else it }
        }
    }
}
