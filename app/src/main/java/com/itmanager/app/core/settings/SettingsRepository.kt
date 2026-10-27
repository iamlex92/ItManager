package com.itmanager.app.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<ServerSettings> = dataStore.data
        .map { prefs ->
            ServerSettings(
                baseUrl = prefs[KEY_BASE_URL].orEmpty(),
                apiToken = prefs[KEY_API_TOKEN].orEmpty(),
            )
        }
        .distinctUntilChanged()

    suspend fun current(): ServerSettings = settings.first()

    /** Biometric login to the app; on unless the operator turned it off. Kept apart from the server form's Save. */
    val appLock: Flow<Boolean> = dataStore.data.map { it[KEY_APP_LOCK] ?: true }.distinctUntilChanged()

    suspend fun setAppLock(enabled: Boolean) {
        dataStore.edit { it[KEY_APP_LOCK] = enabled }
    }

    suspend fun save(settings: ServerSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = settings.baseUrl
            prefs[KEY_API_TOKEN] = settings.apiToken
            // A switch left by older versions of the app.
            prefs.remove(KEY_LEGACY_OFFLINE)
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("server_base_url")
        val KEY_API_TOKEN = stringPreferencesKey("server_api_token")
        val KEY_LEGACY_OFFLINE = booleanPreferencesKey("demo_mode")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock")
    }
}
