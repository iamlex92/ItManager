package com.itmanager.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.core.vpn.FactoryVpn
import com.itmanager.app.core.vpn.VpnSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val vpn: FactoryVpn,
) : ViewModel() {
    /** The form; null until read. Saved field by field, there is no separate Save. */
    private val _form = MutableStateFlow<VpnSettings?>(null)
    val form: StateFlow<VpnSettings?> = _form.asStateFlow()

    init {
        viewModelScope.launch { _form.value = settings.vpnSettings.first() }
    }

    fun edit(change: (VpnSettings) -> VpnSettings) {
        _form.update { it?.let(change) }
        val v = _form.value ?: return
        viewModelScope.launch { settings.saveVpn(v) }
    }
}

/** «VPN завода»: Cisco Secure Client brought up by the app when the server does not answer. */
@Composable
fun VpnSection(viewModel: VpnViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val v = form ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подключать автоматически", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Если сервер не отвечает, приложение само попросит Cisco Secure Client подключиться и вернётся. " +
                        "На работе и при уже поднятом туннеле ничего не происходит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = v.enabled, onCheckedChange = { on -> viewModel.edit { it.copy(enabled = on) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Отключать при выходе", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Выход кнопкой «Назад» с главного экрана отключает VPN завода, если его подключало приложение, " +
                        "и телефон возвращается к вашему VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = v.disconnectOnExit, onCheckedChange = { on -> viewModel.edit { it.copy(disconnectOnExit = on) } })
        }
        OutlinedTextField(
            value = v.name,
            onValueChange = { s -> viewModel.edit { it.copy(name = s) } },
            label = { Text("Подключение в AnyConnect") },
            placeholder = { Text("Завод") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = v.host,
            onValueChange = { s -> viewModel.edit { it.copy(host = s) } },
            label = { Text("Адрес ASA") },
            placeholder = { Text("vpn.zavod.ru") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = v.username,
            onValueChange = { s -> viewModel.edit { it.copy(username = s) } },
            label = { Text("Логин (подставится в форму входа)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = v.isConfigured,
                onClick = { context.findFragmentActivity()?.let { a -> scope.launch { viewModel.vpn.connectNow(a) } } },
            ) { Text("Подключить") }
            OutlinedButton(onClick = { context.findFragmentActivity()?.let(viewModel.vpn::disconnect) }) {
                Text("Отключить VPN завода")
            }
        }
        Text(
            "Один раз в Cisco Secure Client: Настройки → «Внешнее управление» → «Включено» (или «Спрашивать»). " +
                "Пароль приложение не знает: он вводится или хранится в AnyConnect, удобнее вход по сертификату. " +
                "В Android одновременно работает одна VPN: подключение завода заменит ваш личный VPN, а «Отключить» " +
                "вернёт телефон ему (полчаса приложение не будет подключать завод само).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
