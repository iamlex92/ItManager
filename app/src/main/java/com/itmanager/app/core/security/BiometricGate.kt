package com.itmanager.app.core.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.itmanager.app.data.model.AuthMethod
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface GateResult {
    data class Confirmed(val method: AuthMethod) : GateResult
    data object Cancelled : GateResult
    data class Failed(val message: String) : GateResult
}

/**
 * Confirms a critical action with the phone's own lock: fingerprint, Face Unlock, or the screen PIN
 * as a fallback. Weak biometrics are accepted because this is a confirmation, not a key unlock,
 * and Face Unlock on many phones is class 2.
 */
object BiometricGate {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Null when the phone can confirm; otherwise what the operator has to set up first. */
    fun unavailableReason(context: Context): String? =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "На телефоне не настроены отпечаток, Face Unlock или блокировка экрана. Без них критические сценарии не запускаются."
            else -> "Телефон не может подтвердить запуск биометрией или PIN-кодом."
        }

    suspend fun confirm(activity: FragmentActivity, title: String, subtitle: String): GateResult =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val method = if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) {
                            AuthMethod.DEVICE_CREDENTIAL
                        } else {
                            AuthMethod.BIOMETRIC
                        }
                        if (cont.isActive) cont.resume(GateResult.Confirmed(method))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        cont.resume(if (cancelled) GateResult.Cancelled else GateResult.Failed(errString.toString()))
                    }

                    // A wrong finger is not final: the system prompt lets the operator try again.
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .setConfirmationRequired(true)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}

/** The activity behind a Compose context, which may be wrapped (themes, Hilt). */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
