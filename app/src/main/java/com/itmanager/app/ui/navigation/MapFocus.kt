package com.itmanager.app.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** "Show on map" requests from other tabs. The map consumes a request once it has centred on the device. */
@Singleton
class MapFocus @Inject constructor() {
    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    fun request(deviceId: String) {
        _deviceId.value = deviceId
    }

    fun consume() {
        _deviceId.value = null
    }
}
