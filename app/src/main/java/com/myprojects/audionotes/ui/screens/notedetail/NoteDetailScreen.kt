package com.myprojects.audionotes.ui.screens.notedetail

import android.Manifest
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_SUFFIX
import com.myprojects.audionotes.util.PlayerState
import java.util.Locale
import kotlin.math.roundToInt

// --- Models used by parseContentForViewMode ---
sealed interface ViewContentPart
data class TextPart(val text: String) : ViewContentPart
data class AudioPart(val block: NoteBlock) : ViewContentPart

fun parseContentForViewMode(
    textContent: String,
    availableAudioBlocks: List<NoteBlock>
): List<ViewContentPart> {
    val parts = mutableListOf<ViewContentPart>()
    if (textContent.isEmpty() && availableAudioBlocks.isEmpty()) return parts
    if (textContent.isEmpty()) {
        availableAudioBlocks.forEach { parts.add(AudioPart(it)) }
        return parts
    }
    val regex = Regex(
        Regex.escape(AUDIO_PLACEHOLDER_PREFIX) +
                "(\\d+)" +
                Regex.escape(AUDIO_PLACEHOLDER_SUFFIX)
    )
    var lastIndex = 0
    regex.findAll(textContent).forEach { match ->
        if (match.range.first > lastIndex) {
            parts.add(TextPart(textContent.substring(lastIndex, match.range.first)))
        }
        val id = match.groupValues[1].toLongOrNull()
        if (id != null) {
            availableAudioBlocks.find { it.id == id }
                ?.let { parts.add(AudioPart(it)) }
                ?: run {
                    parts.add(TextPart(match.value))
                    Log.w("ViewContentParser", "Missing audio block for ${match.value}")
                }
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < textContent.length) {
        parts.add(TextPart(textContent.substring(lastIndex)))
    }
    return parts.filterNot { it is TextPart && it.text.isEmpty() }
}

fun formatDuration(millis: Long?): String {
    if (millis == null || millis < 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    viewModel: NoteDetailViewModel = hiltViewModel(),
    navController: NavController
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // lift this call into a true @Composable scope
    val contentParts = remember(uiState.textFieldValue.text, uiState.displayedAudioBlocks) {
        parseContentForViewMode(uiState.textFieldValue.text, uiState.displayedAudioBlocks)
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = viewModel::onPermissionResult
    )

    var isEditing by remember(uiState.noteId, uiState.isLoading, uiState.isEditing) {
        mutableStateOf(uiState.isEditing)
    }
    LaunchedEffect(uiState.isEditing) {
        isEditing = uiState.isEditing
    }

    BackHandler(enabled = true) {
        if (isEditing) {
            if (viewModel.hasUnsavedChanges()) {
                viewModel.saveNote(switchToViewModeAfterSave = true)
            } else {
                isEditing = false
            }
        } else {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditing) {
                        BasicTextField(
                            value = uiState.noteTitle,
                            onValueChange = viewModel::updateNoteTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp),
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (uiState.noteTitle.isEmpty()) {
                                        Text(
                                            "Note Title",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                    } else {
                        Text(
                            text = uiState.noteTitle.ifBlank { "Untitled Note" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing) {
                            if (viewModel.hasUnsavedChanges()) {
                                viewModel.saveNote(switchToViewModeAfterSave = true)
                            } else {
                                isEditing = false
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Go back")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(
                            onClick = { viewModel.saveNote(switchToViewModeAfterSave = true) },
                            enabled = !uiState.isSaving
                        ) {
                            if (uiState.isSaving) CircularProgressIndicator(Modifier.size(24.dp))
                            else Icon(Icons.Filled.Done, contentDescription = "Save Note")
                        }
                    } else {
                        IconButton(onClick = {
                            // TODO: confirm delete
                            Log.d("NoteDetailScreen", "Delete clicked")
                        }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Note")
                        }
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit Note")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (isEditing) {
                FloatingActionButton(
                    onClick = {
                        if (uiState.isRecording) viewModel.stopRecordingAudio()
                        else recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                ) {
                    Icon(
                        imageVector = if (uiState.isRecording) Icons.Filled.StopCircle else Icons.Filled.Mic,
                        contentDescription = null
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 72.dp)
        ) {
            if (uiState.isLoading && uiState.noteId == null) {
                item {
                    Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                uiState.error?.let { err ->
                    item {
                        Text(
                            "Error: $err",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                if (isEditing) {
                    item {
                        TextField(
                            value = uiState.textFieldValue,
                            onValueChange = viewModel::onContentChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 250.dp),
                            placeholder = { Text("Write your note here...") },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        )
                    }
                    if (uiState.displayedAudioBlocks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            Text("Attached Audio:", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        items(uiState.displayedAudioBlocks, key = { "edit-${it.id}" }) { block ->
                            AudioPlayerItem(
                                audioBlock = block,
                                isPlaying = uiState.currentPlayingAudioBlockId == block.id && uiState.audioPlayerState == PlayerState.PLAYING,
                                isPaused = uiState.currentPlayingAudioBlockId == block.id && uiState.audioPlayerState == PlayerState.PAUSED,
                                currentPositionMs = if (uiState.currentPlayingAudioBlockId == block.id) uiState.currentAudioPositionMs else 0,
                                totalDurationMs = (block.audioDurationMs ?: 0L).toInt(),
                                onPlayPauseClick = { viewModel.playAudio(block.id) },
                                onDeleteClick = { viewModel.deleteAudioBlockFromPlayer(block.id) },
                                onSeek = { progress ->
                                    viewModel.onSeekAudio(block.id, (progress * (block.audioDurationMs ?: 0L)).roundToInt())
                                },
                                showDeleteButton = true
                            )
                        }
                    }
                } else {
                    if (contentParts.isEmpty() && uiState.textFieldValue.text.isBlank()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                                Text("Note is empty. Tap edit to start writing.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    } else {
                        itemsIndexed(contentParts, key = { idx, part ->
                            when (part) {
                                is TextPart  -> "view-text-$idx-${part.text.hashCode().toString().take(8)}"
                                is AudioPart -> "view-audio-${part.block.id}"
                            }
                        }) { _, part ->
                            when (part) {
                                is TextPart -> SelectionContainer {
                                    Text(part.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
                                }
                                is AudioPart -> AudioPlayerItem(
                                    audioBlock = part.block,
                                    isPlaying = uiState.currentPlayingAudioBlockId == part.block.id && uiState.audioPlayerState == PlayerState.PLAYING,
                                    isPaused = uiState.currentPlayingAudioBlockId == part.block.id && uiState.audioPlayerState == PlayerState.PAUSED,
                                    currentPositionMs = if (uiState.currentPlayingAudioBlockId == part.block.id) uiState.currentAudioPositionMs else 0,
                                    totalDurationMs = (part.block.audioDurationMs ?: 0L).toInt(),
                                    onPlayPauseClick = { viewModel.playAudio(part.block.id) },
                                    onDeleteClick = { /* no-op */ },
                                    onSeek = { progress ->
                                        viewModel.onSeekAudio(part.block.id, (progress * (part.block.audioDurationMs ?: 0L)).roundToInt())
                                    },
                                    showDeleteButton = false
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uiState.showPermissionRationaleDialog && isEditing) {
            AlertDialog(
                onDismissRequest = viewModel::onPermissionRationaleDismissed,
                title = { Text("Permission Required") },
                text = { Text("To record audio notes, this app needs access to your microphone.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.onPermissionRationaleDismissed()
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Grant") }
                },
                dismissButton = {
                    Button(onClick = viewModel::onPermissionRationaleDismissed) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun AudioPlayerItem(
    audioBlock: NoteBlock,
    isPlaying: Boolean,
    isPaused: Boolean,
    currentPositionMs: Int,
    totalDurationMs: Int,
    onPlayPauseClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSeek: (Float) -> Unit,
    showDeleteButton: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Clip ${audioBlock.id % 1000}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatDuration(currentPositionMs.toLong())} / ${formatDuration(totalDurationMs.toLong())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPauseClick) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    if (showDeleteButton) {
                        IconButton(onClick = onDeleteClick) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            if (totalDurationMs > 0) {
                var sliderValue by remember(audioBlock.id, currentPositionMs, totalDurationMs, isPlaying, isPaused) {
                    mutableStateOf(currentPositionMs.toFloat() / totalDurationMs.toFloat())
                }
                var isUserSeeking by remember { mutableStateOf(false) }

                Slider(
                    value = sliderValue.coerceIn(0f, 1f),
                    onValueChange = {
                        isUserSeeking = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        onSeek(sliderValue)
                        isUserSeeking = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                LaunchedEffect(currentPositionMs, totalDurationMs, audioBlock.id, isPlaying) {
                    if (!isUserSeeking) {
                        sliderValue = (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }
}
