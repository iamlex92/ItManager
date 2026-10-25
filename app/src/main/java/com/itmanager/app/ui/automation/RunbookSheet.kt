package com.itmanager.app.ui.automation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.data.model.RunbookParam
import com.itmanager.app.data.model.RunbookParamKind
import com.itmanager.app.data.model.RunbookRun
import com.itmanager.app.ui.theme.MonoStyle

/** Form for one runbook, then its outcome. The sheet cannot be swiped away while a run is in flight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunbookSheet(
    session: RunbookSession,
    onValue: (name: String, value: String) -> Unit,
    onRun: () -> Unit,
    onAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running by rememberUpdatedState(session.running)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !running },
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            val rb = session.runbook
            Text(rb.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(rb.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rb.critical) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Запуск подтверждается отпечатком, Face Unlock или PIN-кодом телефона",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            val result = session.result
            if (result == null) {
                rb.params.forEach { p ->
                    ParamField(p, session.values[p.name].orEmpty(), enabled = !session.running) { onValue(p.name, it) }
                    Spacer(Modifier.height(12.dp))
                }
                session.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = onRun, enabled = !session.running, modifier = Modifier.fillMaxWidth()) {
                    when {
                        session.running -> {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Выполняется…")
                        }
                        rb.critical -> {
                            Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Подтвердить и запустить")
                        }
                        else -> Text("Запустить")
                    }
                }
            } else {
                RunResult(result)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Ещё раз") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Готово") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamField(param: RunbookParam, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val label = if (param.required) param.label else "${param.label} (необязательно)"
    when (param.kind) {
        RunbookParamKind.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = if (param.placeholder != null) { { Text(param.placeholder) } } else null,
            singleLine = true,
            enabled = enabled,
            // Account names and paths: no autocorrect or capitalisation getting in the way.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        RunbookParamKind.CHOICE -> Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                param.choices.forEach { choice ->
                    FilterChip(
                        selected = value == choice.value,
                        onClick = { onChange(choice.value) },
                        enabled = enabled,
                        label = { Text(choice.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunResult(run: RunbookRun) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (run.succeeded) "Выполнено" else "Завершилось с ошибкой",
            style = MaterialTheme.typography.titleMedium,
            color = if (run.succeeded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.weight(1f))
        ExitCodeLabel(run.exitCode)
        Spacer(Modifier.width(12.dp))
        Text(formatDuration(run.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (run.simulated) {
        Text(
            "Эмуляция: реальные AD и серверы не затронуты",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    run.secret?.let {
        Spacer(Modifier.height(12.dp))
        SecretCard(it)
    }
    Spacer(Modifier.height(12.dp))
    TerminalBlock(run.stdout, run.stderr)
}

/**
 * The temporary password, shown once. While it is on screen the window is secure,
 * so it stays out of screenshots and the recent apps preview; a copy is marked sensitive.
 */
@Composable
private fun SecretCard(secret: String) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findFragmentActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text("Временный пароль", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                secret,
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val clip = ClipData.newPlainText("Временный пароль", secret)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                copied = true
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Скопировать", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(
            if (copied) "Скопирован. Передайте пользователю лично, при входе он задаст свой пароль."
            else "Показан один раз и нигде не сохраняется. Пользователь сменит его при входе.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}
