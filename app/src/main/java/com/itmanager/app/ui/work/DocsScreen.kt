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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.DOC_CATEGORIES
import com.itmanager.app.data.model.DocBrief
import com.itmanager.app.data.model.DocDraft
import com.itmanager.app.data.model.DocFull
import com.itmanager.app.data.model.docCategoryLabel
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.FormSection
import com.itmanager.app.ui.components.FormSheet
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.UiState

/** «Документы»: instructions, passports, schemes and notes of the service, searched by title and text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DocsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Документы", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openNew,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Документ") },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                placeholder = { Text("Поиск по названию и тексту") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::load, onOpenSettings)
                is UiState.Content -> {
                    if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (s.data.isEmpty()) {
                        EmptyView(
                            if (query.isBlank()) "Документов пока нет. Заведите паспорт серверной, инструкцию по резервному копированию или заметку."
                            else "Ничего не найдено",
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        items(s.data, key = { it.id }) { doc -> DocRow(doc) { viewModel.open(doc) } }
                    }
                }
            }
        }
    }
    opened?.let { doc ->
        DocReader(doc, onEdit = { viewModel.openEdit(doc) }, onClose = viewModel::closeReader)
    }
    editor?.let { e ->
        DocEditorDialog(e, onChange = viewModel::updateDraft, onSave = viewModel::save, onDelete = viewModel::delete, onDismiss = viewModel::closeEditor)
    }
}

@Composable
private fun DocRow(doc: DocBrief, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (doc.pinned) {
                Icon(Icons.Outlined.PushPin, contentDescription = "Закреплён", modifier = Modifier.padding(end = 6.dp))
            }
            Text(doc.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(docCategoryLabel(doc.category), doc.deviceName).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (doc.excerpt.isNotBlank()) {
            Text(
                doc.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The whole text of a document, full screen; the text can be selected and copied. Markdown marks stay as typed. */
@Composable
private fun DocReader(doc: DocFull, onEdit: () -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                    Column(Modifier.weight(1f)) {
                        Text(doc.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(docCategoryLabel(doc.category), doc.deviceName).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Изменить") }
                }
                SelectionContainer {
                    Text(
                        doc.body.ifBlank { "Текста нет." },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DocEditorDialog(
    editor: DocEditor,
    onChange: (DocDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (DocFull) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = editor.draft
    val editing = editor.editing
    FormSheet(
        title = editing?.title ?: "Новый документ",
        onDismiss = onDismiss,
        onSave = onSave,
        canSave = draft.canSave,
        busy = editor.busy,
        error = editor.error,
        deleteLabel = editing?.let { "Удалить документ" },
        deleteQuestion = editing?.let { "Удалить «${it.title}»?" to "Документ пропадёт совсем." },
        onDelete = editing?.let { d -> { onDelete(d) } },
    ) {
        FormSection("Документ") {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it.take(200))) },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DOC_CATEGORIES.forEach { (value, label) ->
                    FilterChip(selected = draft.category == value, onClick = { onChange(draft.copy(category = value)) }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                value = draft.body,
                onValueChange = { onChange(draft.copy(body = it.take(200_000))) },
                label = { Text("Текст") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Закрепить вверху списка", Modifier.weight(1f))
                Switch(checked = draft.pinned, onCheckedChange = { onChange(draft.copy(pinned = it)) })
            }
            Text(
                "Привязать документ к устройству можно в веб-консоли.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
