package com.itmanager.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.core.nfc.TagScan
import com.itmanager.app.core.security.AppLock
import com.itmanager.app.data.alerts.AlertCenter
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where something outside the current screen (a tag, an alert) wants to take the user. */
sealed interface AppNavigation {
    data class OpenSwitch(val deviceId: String) : AppNavigation
    data object ShowOnMap : AppNavigation
}

/** An alert that just arrived, with the device name resolved for the banner. */
data class AlertNotice(val alert: Alert, val deviceName: String?)

/** App-wide behaviour that is not tied to one screen: NFC taps, the alert badge and in-app notifications. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val nfc: NfcController,
    private val alertCenter: AlertCenter,
    private val mapFocus: MapFocus,
    private val appLock: AppLock,
) : ViewModel() {

    val lock = appLock.state

    fun unlock() = appLock.unlock()

    val binding = nfc.binding

    val openAlertCount: StateFlow<Int> = alertCenter.state
        .map { s -> (s as? UiState.Content)?.data?.count { !it.isResolved } ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _notice = MutableStateFlow<AlertNotice?>(null)
    val notice: StateFlow<AlertNotice?> = _notice.asStateFlow()

    private val _navigation = MutableSharedFlow<AppNavigation>(extraBufferCapacity = 4)
    val navigation: SharedFlow<AppNavigation> = _navigation.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        alertCenter.start(viewModelScope)
        viewModelScope.launch { nfc.scans.collect(::openTag) }
    }

    /** Collected only while the app is on screen, so a pocketed phone does not keep the socket open. */
    suspend fun followAlerts() = alertCenter.follow { alert ->
        _notice.value = AlertNotice(alert, alertCenter.nameOf(alert.deviceId))
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun openNotice() {
        val notice = _notice.value ?: return
        _notice.value = null
        openDevice(notice.alert.deviceId)
    }

    /** Switches open on their port panel; everything else is shown on the map. */
    fun openDevice(deviceId: String) {
        val device = alertCenter.devices.value[deviceId]
        if (device == null) {
            // A printer known only to the SNMP poller, or a device list that has not loaded yet.
            _messages.tryEmit("Этого устройства нет на карте")
            return
        }
        open(device)
    }

    private fun open(device: Device) {
        if (device.type == DeviceType.SWITCH) {
            _navigation.tryEmit(AppNavigation.OpenSwitch(device.id))
        } else {
            mapFocus.request(device.id)
            _navigation.tryEmit(AppNavigation.ShowOnMap)
        }
    }

    private suspend fun openTag(scan: TagScan) {
        repository.deviceForTag(scan.payload?.id, scan.uid)
            .onSuccess(::open)
            .onFailure { _messages.emit(it.toUserMessage()) }
    }

    // Binding sheet

    fun nfcAvailability() = nfc.availability()

    fun confirmMove() = nfc.confirmMove()

    fun cancelBinding() = nfc.cancelBinding()
}
