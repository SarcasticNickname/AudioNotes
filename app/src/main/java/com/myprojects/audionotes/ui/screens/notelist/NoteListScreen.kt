package com.myprojects.audionotes.ui.screens.notelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications // Для иконки напоминания
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.ui.theme.AudioNotesTheme
import com.myprojects.audionotes.ui.viewmodel.NoteListViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel = hiltViewModel(),
    onNoteClick: (Long) -> Unit,
    onAddNoteClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                    viewModel.createNewNote(onAddNoteClick)
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Note")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.error != null -> {
                    Text(
                        text = "Error: ${uiState.error}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                uiState.notes.isEmpty() -> {
                    Text(
                        text = "No notes yet. Tap '+' to add one!",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> {
                    NoteList(
                        notes = uiState.notes,
                        onNoteClick = onNoteClick,
                        onDeleteClick = { noteId ->
                            noteToDeleteId = noteId
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showDeleteDialog && noteToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to delete this note? This will also remove any scheduled reminder.") },
            confirmButton = {
                Button(
                    onClick = {
                        noteToDeleteId?.let { viewModel.deleteNote(it) }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) { Text("Cancel") }
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
        contentPadding = PaddingValues(all = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(notes, key = { note -> note.id }) { note ->
            NoteItem(
                note = note,
                onClick = { onNoteClick(note.id) },
                onDeleteClick = { onDeleteClick(note.id) }
            )
        }
    }
}

private val noteItemDateFormatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
private val reminderDateFormatter = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

@Composable
fun NoteItem(
    note: Note,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note.title.ifEmpty { "Untitled Note" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (note.reminderAt != null && note.reminderAt!! > System.currentTimeMillis()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "Reminder set",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Updated: ${noteItemDateFormatter.format(Date(note.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note.reminderAt != null && note.reminderAt!! > System.currentTimeMillis()) {
                    Text(
                        text = "Напомнить: ${reminderDateFormatter.format(Date(note.reminderAt!!))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Note",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun NoteListScreenPreview() {
    AudioNotesTheme {
        val previewNotes = listOf(
            Note(
                id = 1,
                title = "Shopping List",
                updatedAt = System.currentTimeMillis() - 100000,
                reminderAt = System.currentTimeMillis() + 3600000
            ),
            Note(
                id = 2,
                title = "Meeting Notes - Project X",
                updatedAt = System.currentTimeMillis() - 5000000
            ),
            Note(
                id = 3,
                title = "An extremely long title that should definitely be ellipsized",
                updatedAt = System.currentTimeMillis() - 10000000,
                reminderAt = System.currentTimeMillis() - 3600000
            )
        )
        Scaffold(
            topBar = { TopAppBar(title = { Text("Audio Notes Preview") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) {
                    Icon(
                        Icons.Default.Add,
                        ""
                    )
                }
            }
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
        Column(Modifier.padding(8.dp)) {
            NoteItem(
                note = Note(
                    id = 1, title = "A Very Long Note Title That Should Be Ellipsized",
                    updatedAt = System.currentTimeMillis(),
                    reminderAt = System.currentTimeMillis() + 7200000
                ),
                onClick = {}, onDeleteClick = {}
            )
            Spacer(Modifier.height(8.dp))
            NoteItem(
                note = Note(
                    id = 2,
                    title = "Short note",
                    updatedAt = System.currentTimeMillis() - 86400000
                ),
                onClick = {}, onDeleteClick = {}
            )
        }
    }
}