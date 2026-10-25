package com.itmanager.app.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.itmanager.app.core.security.BiometricGate
import com.itmanager.app.core.security.GateResult
import com.itmanager.app.core.security.LockState
import com.itmanager.app.core.security.findFragmentActivity
import kotlinx.coroutines.flow.first

/**
 * Covers the whole app until the operator unlocks it. A dialog window rather than an overlay in the layout,
 * so it also sits above sheets and dialogs that were open when the app locked.
 */
@Composable
fun AppLockOverlay(state: LockState, lifecycleOwner: LifecycleOwner, onUnlocked: () -> Unit) {
    if (state == LockState.UNLOCKED) return
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    Dialog(
        // Back leaves the app instead of reaching the content behind the lock.
        onDismissRequest = { activity?.moveTaskToBack(true) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (state == LockState.LOCKED) LockContent(lifecycleOwner, onUnlocked)
        }
    }
}

@Composable
private fun LockContent(lifecycleOwner: LifecycleOwner, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val unavailable = remember(context) { BiometricGate.unavailableReason(context) }
    var attempt by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        if (unavailable != null) return@LaunchedEffect
        val activity = context.findFragmentActivity() ?: return@LaunchedEffect
        // The prompt needs a resumed activity; once shown it must outlive the pause when the PIN screen opens.
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        message = null
        when (val r = BiometricGate.confirm(activity, "Вход в NetAdmin", "Подтвердите, что это вы")) {
            is GateResult.Confirmed -> onUnlocked()
            // The button below asks again.
            GateResult.Cancelled -> Unit
            is GateResult.Failed -> message = r.message
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("NetAdmin заблокирован", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            unavailable?.let { "$it Настройте блокировку экрана, чтобы вход в приложение был защищён." }
                ?: message
                ?: "Отпечаток, Face Unlock или PIN-код телефона.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            // Without a phone lock there is nothing to check against; the app opens and says why.
            onClick = { if (unavailable != null) onUnlocked() else attempt++ },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (unavailable != null) "Открыть" else "Разблокировать") }
    }
}
