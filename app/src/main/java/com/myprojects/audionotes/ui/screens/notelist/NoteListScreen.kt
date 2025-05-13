package com.myprojects.audionotes.ui.screens.notelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete // Для кнопки удаления
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.ui.theme.AudioNotesTheme
import com.myprojects.audionotes.ui.viewmodel.NoteListViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class) // Для Scaffold и TopAppBar
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel = hiltViewModel(), // Получаем ViewModel через Hilt
    onNoteClick: (Long) -> Unit, // Callback для клика по заметке
    onAddNoteClick: (Long) -> Unit // Callback после создания заметки
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle() // Подписываемся на состояние

    // Состояние для диалога подтверждения удаления
    var showDeleteDialog by remember { mutableStateOf(false) }
    var noteToDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Notes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Вызываем функцию ViewModel для создания заметки
                    // Передаем onAddNoteClick как callback, который ViewModel вызовет с ID новой заметки
                    viewModel.createNewNote(onAddNoteClick)
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Note")
            }
        }
    ) { paddingValues -> // Содержимое экрана с отступами от Scaffold
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)) { // Применяем отступы

            when {
                // Состояние загрузки
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                // Состояние ошибки
                uiState.error != null -> {
                    Text(
                        text = "Error: ${uiState.error}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                // Список пуст
                uiState.notes.isEmpty() -> {
                    Text(
                        text = "No notes yet. Tap '+' to add one!",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                // Отображение списка заметок
                else -> {
                    NoteList(
                        notes = uiState.notes,
                        onNoteClick = onNoteClick,
                        onDeleteClick = { noteId ->
                            // Показываем диалог подтверждения перед удалением
                            noteToDeleteId = noteId
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    // Диалог подтверждения удаления
    if (showDeleteDialog && noteToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false }, // Закрыть диалог при клике вне его
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to delete this note?") },
            confirmButton = {
                Button(
                    onClick = {
                        noteToDeleteId?.let { viewModel.deleteNote(it) } // Вызываем удаление в ViewModel
                        showDeleteDialog = false // Закрываем диалог
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) { // Кнопка отмены
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun NoteList(
    notes: List<Note>,
    onNoteClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), // Отступы для списка
        verticalArrangement = Arrangement.spacedBy(8.dp) // Пространство между элементами
    ) {
        items(notes, key = { note -> note.id }) { note -> // Используем ID как ключ для оптимизации
            NoteItem(
                note = note,
                onClick = { onNoteClick(note.id) },
                onDeleteClick = { onDeleteClick(note.id) }
            )
        }
    }
}

// Форматтер для даты (лучше вынести в util или использовать DI)
private val dateFormatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

@Composable
fun NoteItem(
    note: Note,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick), // Делаем карточку кликабельной
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) { // Основной контент занимает все доступное место
                Text(
                    text = note.title.ifEmpty { "Untitled Note" }, // Заголовок
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Updated: ${dateFormatter.format(Date(note.updatedAt))}", // Дата обновления
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Кнопка удаления
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Note",
                    tint = MaterialTheme.colorScheme.error // Красный цвет для иконки удаления
                )
            }
        }
    }
}


// Preview для NoteListScreen (не будет работать с ViewModel напрямую без Hilt Preview)
@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun NoteListScreenPreview() {
    AudioNotesTheme {
        // Создаем фейковые данные для превью
        val previewNotes = listOf(
            Note(id = 1, title = "Shopping List", updatedAt = System.currentTimeMillis() - 100000),
            Note(id = 2, title = "Meeting Notes - Project X", updatedAt = System.currentTimeMillis() - 5000000),
            Note(id = 3, title = "", updatedAt = System.currentTimeMillis() - 10000000) // Untitled
        )
        // Используем Scaffold для похожего вида
        Scaffold(
            topBar = { TopAppBar(title = { Text("Audio Notes Preview") }) },
            floatingActionButton = { FloatingActionButton(onClick = {}){ Icon(Icons.Default.Add, "")} }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                NoteList(notes = previewNotes, onNoteClick = {}, onDeleteClick = {})
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NoteItemPreview() {
    AudioNotesTheme {
        NoteItem(
            note = Note(id = 1, title = "A Very Long Note Title That Should Be Ellipsized", updatedAt = System.currentTimeMillis()),
            onClick = {},
            onDeleteClick = {}
        )
    }
}