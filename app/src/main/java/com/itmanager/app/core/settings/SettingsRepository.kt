package com.itmanager.app.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.itmanager.app.core.security.TokenCipher
import com.itmanager.app.core.vpn.VpnSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How the app reaches the NetAdmin server. Nothing is shown until [baseUrl] is set. */
data class ServerSettings(
    val baseUrl: String = "",
    val apiToken: String = "",
    /** A second NetAdmin server the app turns to while this one cannot be reached; empty: none. */
    val backupUrl: String = "",
    /** The backup server's token; empty: the same as [apiToken]. */
    val backupToken: String = "",
    /** Who is using the app, for the server's journal: sent with every request. Not a login. */
    val operatorName: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** The backup server as a server of its own, or null when none is set. */
    fun backup(): ServerSettings? =
        backupUrl.takeIf { it.isNotBlank() }?.let { ServerSettings(baseUrl = it, apiToken = backupToken.ifBlank { apiToken }, operatorName = operatorName) }
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<ServerSettings> = dataStore.data
        .map { prefs ->
            ServerSettings(
                baseUrl = prefs[KEY_BASE_URL].orEmpty(),
                apiToken = TokenCipher.decrypt(prefs[KEY_API_TOKEN].orEmpty()),
                backupUrl = prefs[KEY_BACKUP_URL].orEmpty(),
                backupToken = TokenCipher.decrypt(prefs[KEY_BACKUP_TOKEN].orEmpty()),
                operatorName = prefs[KEY_OPERATOR].orEmpty(),
            )
        }
        .distinctUntilChanged()

    suspend fun current(): ServerSettings {
        encryptStoredTokens()
        return settings.first()
    }

    /** Tokens saved by an older version sit in the storage as plain text: they are encrypted once, on first use. */
    @Volatile private var tokensChecked = false

    private suspend fun encryptStoredTokens() {
        if (tokensChecked) return
        tokensChecked = true
        val prefs = dataStore.data.first()
        val main = prefs[KEY_API_TOKEN].orEmpty()
        val backup = prefs[KEY_BACKUP_TOKEN].orEmpty()
        if ((main.isNotEmpty() && !TokenCipher.isEncrypted(main)) || (backup.isNotEmpty() && !TokenCipher.isEncrypted(backup))) {
            dataStore.edit { p ->
                p[KEY_API_TOKEN] = TokenCipher.encrypt(TokenCipher.decrypt(main))
                p[KEY_BACKUP_TOKEN] = TokenCipher.encrypt(TokenCipher.decrypt(backup))
            }
        }
    }

    /** Biometric login to the app; on unless the operator turned it off. Kept apart from the server form's Save. */
    val appLock: Flow<Boolean> = dataStore.data.map { it[KEY_APP_LOCK] ?: true }.distinctUntilChanged()

    suspend fun setAppLock(enabled: Boolean) {
        dataStore.edit { it[KEY_APP_LOCK] = enabled }
    }

    /** "dark" (the default: server rooms, shop floor), "light" or "system". */
    val themeMode: Flow<String> = dataStore.data.map { it[KEY_THEME] ?: "dark" }.distinctUntilChanged()

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[KEY_THEME] = mode }
    }

    /** The factory VPN through Cisco Secure Client; off until set up. */
    val vpnSettings: Flow<VpnSettings> = dataStore.data
        .map { p ->
            VpnSettings(
                enabled = p[KEY_VPN_ON] ?: false,
                name = p[KEY_VPN_NAME].orEmpty(),
                host = p[KEY_VPN_HOST].orEmpty(),
                username = p[KEY_VPN_USER].orEmpty(),
                disconnectOnExit = p[KEY_VPN_EXIT] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun vpn(): VpnSettings = vpnSettings.first()

    suspend fun saveVpn(vpn: VpnSettings) {
        dataStore.edit { p ->
            p[KEY_VPN_ON] = vpn.enabled
            p[KEY_VPN_NAME] = vpn.name.trim()
            p[KEY_VPN_HOST] = vpn.host.trim()
            p[KEY_VPN_USER] = vpn.username.trim()
            p[KEY_VPN_EXIT] = vpn.disconnectOnExit
        }
    }

    suspend fun save(settings: ServerSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = settings.baseUrl
            prefs[KEY_API_TOKEN] = TokenCipher.encrypt(settings.apiToken)
            prefs[KEY_BACKUP_URL] = settings.backupUrl
            prefs[KEY_BACKUP_TOKEN] = TokenCipher.encrypt(settings.backupToken)
            prefs[KEY_OPERATOR] = settings.operatorName
            // A switch left by older versions of the app.
            prefs.remove(KEY_LEGACY_OFFLINE)
        }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_BASE_URL = stringPreferencesKey("server_base_url")
        val KEY_API_TOKEN = stringPreferencesKey("server_api_token")
        val KEY_BACKUP_URL = stringPreferencesKey("backup_base_url")
        val KEY_BACKUP_TOKEN = stringPreferencesKey("backup_api_token")
        val KEY_OPERATOR = stringPreferencesKey("operator_name")
        val KEY_LEGACY_OFFLINE = booleanPreferencesKey("demo_mode")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock")
        val KEY_VPN_ON = booleanPreferencesKey("vpn_auto")
        val KEY_VPN_NAME = stringPreferencesKey("vpn_name")
        val KEY_VPN_HOST = stringPreferencesKey("vpn_host")
        val KEY_VPN_USER = stringPreferencesKey("vpn_user")
        val KEY_VPN_EXIT = booleanPreferencesKey("vpn_disconnect_on_exit")
    }
}
