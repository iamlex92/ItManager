package com.itmanager.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.itmanager.app.core.security.BiometricGate
import com.itmanager.app.core.security.GateResult
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.theme.Motion
import com.itmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCredentials: () -> Unit,
    /** First launch: a welcome, the server first, and nothing else to distract. */
    welcome: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (welcome) "Подключение к серверу" else "Сервер и безопасность") },
                navigationIcon = {
                    if (!welcome) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp),
        ) {
            if (welcome) {
                Text("Добро пожаловать в ItManager", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Все данные (карта, устройства, сеть, события) хранятся на сервере NetAdmin вашего предприятия. " +
                        "Отсканируйте его QR-код или введите адрес и ключ — это нужно один раз.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SectionLabel("Безопасность")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Вход по биометрии", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Отпечаток, Face Unlock или PIN при запуске и после 5 минут в фоне.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.appLock,
                        onCheckedChange = { on ->
                            if (on) {
                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                viewModel.setAppLock(true)
                                return@Switch
                            }
                            // Whoever holds the unlocked phone should not switch the protection off unnoticed.
                            val activity = context.findFragmentActivity() ?: return@Switch
                            if (BiometricGate.unavailableReason(context) != null) {
                                viewModel.setAppLock(false)
                                return@Switch
                            }
                            scope.launch {
                                val r = BiometricGate.confirm(activity, "Выключить вход по биометрии", "Подтвердите, что это вы")
                                if (r is GateResult.Confirmed) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                    viewModel.setAppLock(false)
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(role = Role.Button, onClick = onOpenCredentials)
                        .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Учётные данные", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Логины SSH, SNMPv3 и WinRM. Секреты хранятся на сервере зашифрованными и в приложение не возвращаются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }

            }
            Spacer(Modifier.height(24.dp))
            SectionLabel("Сервер NetAdmin")
            OutlinedButton(
                onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .enableAutoZoom()
                        .build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code ->
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            viewModel.applyScanned(code.rawValue.orEmpty())
                        }
                        .addOnFailureListener {
                            viewModel.scanFailed()
                        }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Сканировать QR-код сервера")
            }
            Text(
                "Код показывает ярлык «Адрес и ключ для приложения» на сервере Windows или sudo netadmin-agent info на Debian.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                label = { Text("Адрес сервера") },
                placeholder = { Text("http://10.0.0.5:8000") },
                singleLine = true,
                isError = state.urlError != null,
                supportingText = {
                    Text(state.urlError ?: "IP или имя в сети завода. Порт по умолчанию 8000.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            var tokenVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = state.apiToken,
                onValueChange = viewModel::setApiToken,
                label = { Text("API-токен") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (tokenVisible) "Скрыть токен" else "Показать токен",
                        )
                    }
                },
                supportingText = { Text("Выдаётся администратором сервера") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = viewModel::checkConnection,
                enabled = state.baseUrl.isNotBlank() && state.check != ConnectionCheck.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить соединение") }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = state.check,
                transitionSpec = { fadeIn(Motion.smooth()) togetherWith fadeOut(Motion.smooth()) },
                label = "connectionCheck",
            ) { check -> ConnectionCheckResult(check) }

            Spacer(Modifier.height(12.dp))
            Text(
                "Сервер доступен только из сети завода или через VPN. Если телефон вне завода, сначала включите VPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (viewModel.save()) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onBack()
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                },
                enabled = state.hasChanges,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Сохранить") }
            Spacer(Modifier.height(16.dp))
            val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            Text(
                "Приложение ${appVersion ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionCheckResult(check: ConnectionCheck) {
    when (check) {
        ConnectionCheck.Idle -> Spacer(Modifier.height(0.dp))
        ConnectionCheck.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Проверяю…", style = MaterialTheme.typography.bodyMedium)
        }
        is ConnectionCheck.Ok -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = StatusColors.Online)
            Column {
                Text("Сервер отвечает", style = MaterialTheme.typography.titleSmall)
                val h = check.health
                val mode = when (h.halMode) {
                    "emulator" -> "эмулятор оборудования"
                    "real" -> if (h.deviceWritesEnabled == true) "реальное оборудование, изменения разрешены" else "реальное оборудование, только чтение"
                    else -> null
                }
                val secrets = when (h.secretsEncrypted) {
                    true -> "секреты зашифрованы"
                    false -> "секреты не зашифрованы: на сервере не задан SECRET_KEY"
                    null -> null
                }
                Text(
                    listOfNotNull(
                        h.version?.let { "Версия сервера $it" },
                        mode,
                        secrets,
                        "уведомления в Telegram включены".takeIf { h.telegramAlerts == true },
                        when {
                            h.runbookMode != "real" -> null
                            h.runbookWritesEnabled == true -> "runbooks: реальный AD, изменения разрешены"
                            else -> "runbooks: реальный AD, только чтение"
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is ConnectionCheck.Failed -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = StatusColors.Offline)
            Text(check.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
