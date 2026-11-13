package com.itmanager.app.ui.work

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.TASK_REPEATS
import com.itmanager.app.data.model.TaskDraft
import com.itmanager.app.data.model.TaskItem
import com.itmanager.app.data.model.daysLeftText
import com.itmanager.app.data.model.repeatLabel
import com.itmanager.app.data.model.shortDate
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.FormSection
import com.itmanager.app.ui.components.FormSheet
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.StatusColors
import java.time.LocalDate

/** «Задачи»: what is to be done, overdue first. A tick finishes a task; a repeating one comes back by itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val showDone by viewModel.showDone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Задачи", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = viewModel::load) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openNew() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Задача") },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilterChip(
                    selected = showDone,
                    onClick = { viewModel.setShowDone(!showDone) },
                    label = { Text("Показать выполненные") },
                )
            }
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::load, onOpenSettings)
                is UiState.Content -> {
                    if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    TaskList(s.data, onToggle = viewModel::toggle, onOpen = viewModel::openEdit)
                }
            }
        }
    }
    editor?.let { e ->
        TaskEditorDialog(e, onChange = viewModel::updateDraft, onSave = viewModel::save, onDelete = viewModel::delete, onDismiss = viewModel::closeEditor)
    }
}

@Composable
private fun TaskList(tasks: List<TaskItem>, onToggle: (TaskItem) -> Unit, onOpen: (TaskItem) -> Unit) {
    val open = tasks.filter { !it.isDone }
    val done = tasks.filter { it.isDone }
    if (tasks.isEmpty()) {
        EmptyView("Задач нет. Добавьте первую кнопкой ниже: например, «Проверить батарею ИБП» с повтором раз в месяц.")
        return
    }
    val groups = listOf(
        "Просрочено" to open.filter { (it.daysLeft ?: 1) < 0 },
        "Сегодня и ближайшая неделя" to open.filter { (it.daysLeft ?: -1) in 0..7 },
        "Позже" to open.filter { (it.daysLeft ?: 0) > 7 },
        "Без срока" to open.filter { it.daysLeft == null },
        "Выполнено" to done,
    ).filter { it.second.isNotEmpty() }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        groups.forEach { (title, items) ->
            item(key = "h:$title") {
                Spacer(Modifier.height(6.dp))
                SectionLabel("$title · ${items.size}")
            }
            items(items, key = { it.id }) { task -> TaskRow(task, onToggle, onOpen) }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, onToggle: (TaskItem) -> Unit, onOpen: (TaskItem) -> Unit) {
    val overdue = !task.isDone && (task.daysLeft ?: 0) < 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .clickable { onOpen(task) }
            .padding(end = 12.dp),
    ) {
        Checkbox(checked = task.isDone, onCheckedChange = { onToggle(task) })
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            val line = listOfNotNull(
                task.dueDate?.let { d -> "${shortDate(d)}${task.daysLeft?.let { " · ${daysLeftText(it)}" }.orEmpty()}" },
                repeatLabel(task.repeat, task.repeatEvery),
                task.deviceName,
            ).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) StatusColors.Offline else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorDialog(
    editor: TaskEditor,
    onChange: (TaskDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (TaskItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = editor.draft
    val editing = editor.editing
    FormSheet(
        title = editing?.title ?: "Новая задача",
        onDismiss = onDismiss,
        onSave = onSave,
        canSave = draft.canSave,
        busy = editor.busy,
        error = editor.error,
        deleteLabel = editing?.let { "Удалить задачу" },
        deleteQuestion = editing?.let { "Удалить «${it.title}»?" to "Задача пропадёт совсем. Если она сделана, лучше отметьте её галочкой." },
        onDelete = editing?.let { t -> { onDelete(t) } },
    ) {
        FormSection("Что сделать") {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it.take(200))) },
                label = { Text("Задача") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { onChange(draft.copy(notes = it.take(4000))) },
                label = { Text("Заметка") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection("Срок") {
            OutlinedTextField(
                value = draft.dueDate,
                onValueChange = { onChange(draft.copy(dueDate = it.take(10))) },
                label = { Text("Дата") },
                placeholder = { Text("2026-12-31") },
                singleLine = true,
                isError = draft.dateError != null,
                supportingText = draft.dateError?.let { msg -> { Text(msg) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { onChange(draft.copy(dueDate = LocalDate.now().toString())) }, label = { Text("Сегодня") })
                AssistChip(onClick = { onChange(draft.copy(dueDate = LocalDate.now().plusDays(1).toString())) }, label = { Text("Завтра") })
                AssistChip(onClick = { onChange(draft.copy(dueDate = LocalDate.now().plusDays(7).toString())) }, label = { Text("Через неделю") })
                AssistChip(onClick = { onChange(draft.copy(dueDate = "")) }, label = { Text("Без срока") })
            }
        }
        FormSection("Повтор") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TASK_REPEATS.forEach { (value, label) ->
                    FilterChip(selected = draft.repeat == value, onClick = { onChange(draft.copy(repeat = value)) }, label = { Text(label) })
                }
            }
            if (draft.repeat != "none") {
                OutlinedTextField(
                    value = draft.repeatEvery,
                    onValueChange = { onChange(draft.copy(repeatEvery = it.filter(Char::isDigit).take(2))) },
                    label = { Text("Через сколько периодов (1 — каждый)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Когда вы отметите задачу выполненной, следующая появится сама.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
