package com.itmanager.app.core.vpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the factory network up when the app opens away from it: if the NetAdmin server does not answer,
 * Cisco Secure Client is asked to connect and the app reloads once it says it did. Only on the app's start or
 * return and at most once a minute, so a failed attempt does not loop; never while the server answers (at work,
 * or with the tunnel already up). Android has one VPN at a time: connecting replaces a personal one, and
 * «Отключить VPN завода» gives the phone back to it.
 */
@Singleton
class FactoryVpn @Inject constructor(
    private val settings: SettingsRepository,
    private val inventory: DcimRepository,
    @ApplicationContext private val context: Context,
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var lastAttempt = 0L

    /** «Отключать при выходе» as read on the last connect: then a notification can disconnect after Home too. */
    private var noticeWanted = false

    /** The tunnel is up because this app asked for it (AnyConnect said so); a manual one is left alone. */
    private val connectedByApp = MutableStateFlow(false)

    /** Back on the main screen should disconnect first: the setting is on and the app brought the tunnel up. */
    val disconnectOnExit: Flow<Boolean> =
        combine(settings.vpnSettings, connectedByApp) { s, mine -> s.disconnectOnExit && mine }

    /** Leaving the app: disconnect, and AnyConnect hands back only so the app can go to the background. */
    fun disconnectAndLeave(activity: Activity) {
        connectedByApp.value = false
        VpnNotice.cancel(context)
        lastAttempt = SystemClock.elapsedRealtime()
        open(activity, AnyConnect.disconnectUri(leaving = true))
    }

    /** On the app's start or return, once it is unlocked. */
    suspend fun connectIfNeeded(activity: Activity) {
        val vpn = settings.vpn()
        if (!vpn.enabled || !vpn.isConfigured) return
        val now = SystemClock.elapsedRealtime()
        if (lastAttempt != 0L && now - lastAttempt < RETRY_MS) return
        val server = serverEndpoint(settings.current().baseUrl) ?: return
        if (reachable(server)) return
        lastAttempt = now
        noticeWanted = vpn.disconnectOnExit
        _messages.tryEmit("Сервер не отвечает — подключаю VPN завода")
        connect(activity, vpn)
    }

    suspend fun connectNow(activity: Activity) {
        val vpn = settings.vpn()
        if (!vpn.isConfigured) {
            _messages.tryEmit("Укажите подключение AnyConnect: название или адрес ASA")
            return
        }
        lastAttempt = SystemClock.elapsedRealtime()
        noticeWanted = vpn.disconnectOnExit
        connect(activity, vpn)
    }

    fun disconnect(activity: Activity) {
        // Not again on the next return to the app: the person switched it off on purpose.
        lastAttempt = SystemClock.elapsedRealtime() + DISCONNECT_PAUSE_MS
        connectedByApp.value = false
        VpnNotice.cancel(context)
        open(activity, AnyConnect.disconnectUri())
    }

    /** itmanager://vpn/… from AnyConnect; null when it was not one. */
    fun onCallback(uri: Uri?): VpnResult? {
        val result = uri?.toString()?.let(AnyConnect::result) ?: return null
        when (result) {
            VpnResult.CONNECTED -> {
                connectedByApp.value = true
                if (noticeWanted) VpnNotice.show(context)
                _messages.tryEmit("VPN завода подключён")
                inventory.inventoryChanged() // every screen loads again
            }
            VpnResult.FAILED -> _messages.tryEmit(
                "AnyConnect не подключился. Проверьте вход в Cisco Secure Client; повторить — «Подключить» в настройках.",
            )
            VpnResult.DISCONNECTED, VpnResult.LEFT -> {
                // LEFT also comes from the notification's button, after Home: the app is no longer the one connected.
                connectedByApp.value = false
                VpnNotice.cancel(context)
                _messages.tryEmit(if (result == VpnResult.LEFT) "VPN завода отключён" else "VPN завода отключён — можно включить свой")
            }
        }
        return result
    }

    private fun connect(activity: Activity, vpn: VpnSettings) = open(activity, AnyConnect.connectUri(vpn))

    private fun open(activity: Activity, uri: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
            _messages.tryEmit("Не найден Cisco Secure Client: установите его из Google Play")
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AnyConnect.PACKAGE}")))
            }
        }
    }

    private suspend fun reachable(server: Pair<String, Int>): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(server.first, server.second), PROBE_MS) } }.isSuccess
    }

    private companion object {
        const val RETRY_MS = 60_000L
        const val DISCONNECT_PAUSE_MS = 30 * 60_000L
        const val PROBE_MS = 1_500
    }
}
