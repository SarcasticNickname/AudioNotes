package com.myprojects.audionotes.ui.screens.notedetail

import androidx.compose.ui.text.input.TextFieldValue
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.util.PlayerState

data class NoteDetailUiState(
    val noteId: Long? = null,
    val noteTitle: String = "New Note",
    val textFieldValue: TextFieldValue = TextFieldValue(""),
    val displayedAudioBlocks: List<NoteBlock> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSaving: Boolean = false,
    val isRecording: Boolean = false,

    // Состояния для текущего активного плеера
    val currentPlayingAudioBlockId: Long? = null,
    val audioPlayerState: PlayerState = PlayerState.IDLE,
    val currentAudioPositionMs: Int = 0,
    val currentAudioTotalDurationMs: Int = 0,

    val showPermissionRationaleDialog: Boolean = false,
    val permissionGranted: Boolean = false,
    val isEditing: Boolean = false
)