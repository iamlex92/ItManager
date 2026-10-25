package com.itmanager.app.ui.nfc

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.itmanager.app.core.nfc.BindState
import com.itmanager.app.core.nfc.BindStep
import com.itmanager.app.core.nfc.NfcAvailability
import com.itmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.delay

/**
 * Binding a tag to a device: hold the tag, the server records its UID, the tag gets the device's payload.
 * Only the inventory and the tag change; the device itself is never touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindTagSheet(
    state: BindState,
    checkAvailability: () -> NfcAvailability,
    onConfirmMove: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Re-checked on return from system settings, where the user may have just switched NFC on.
    var availability by remember { mutableStateOf(checkAvailability()) }
    LifecycleResumeEffect(Unit) {
        availability = checkAvailability()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.step) {
        when (state.step) {
            BindStep.Done -> {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                delay(1_500)
                onClose()
            }
            is BindStep.Conflict -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            is BindStep.Waiting -> if (state.step.retry != null) haptics.performHapticFeedback(HapticFeedbackType.Reject)
            BindStep.Working -> Unit
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text("NFC-метка для ${state.device.name}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            when {
                availability == NfcAvailability.MISSING -> Message("В этом телефоне нет NFC.")
                availability == NfcAvailability.DISABLED -> {
                    Message("NFC выключен. Включите его в настройках телефона.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                        Text("Открыть настройки NFC")
                    }
                }
                else -> when (val step = state.step) {
                    is BindStep.Waiting -> {
                        PulsingTag()
                        Spacer(Modifier.height(16.dp))
                        Message("Поднесите метку к задней стороне телефона и подержите секунду.")
                        step.retry?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusColors.Warning, textAlign = TextAlign.Center)
                        }
                        if (state.force) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Метка будет перенесена на ${state.device.name}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    BindStep.Working -> {
                        CircularProgressIndicator(Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Message("Привязываю и записываю. Не убирайте метку.")
                    }
                    is BindStep.Conflict -> {
                        Message("Эта метка уже привязана к ${step.otherDevice ?: "другому устройству"}. Перенести её на ${state.device.name}?")
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onClose) { Text("Отмена") }
                            Button(
                                onClick = onConfirmMove,
                                colors = ButtonDefaults.buttonColors(containerColor = StatusColors.Warning),
                            ) { Text("Перенести") }
                        }
                    }
                    BindStep.Done -> {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = StatusColors.Online, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Message("Готово. Метка открывает ${state.device.name}.")
                    }
                }
            }
            if (state.step !is BindStep.Conflict && state.step != BindStep.Done) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onClose) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

/** A slow breathing ring: the phone is listening. */
@Composable
private fun PulsingTag() {
    val transition = rememberInfiniteTransition(label = "nfc")
    val scale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "scale",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Box(
            Modifier
                .size(80.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = 1.4f - scale
                }
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
        )
        Icon(Icons.Outlined.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
    }
}
