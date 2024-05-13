package com.myprojects.audionotes.ui.screens.notedetail

import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.util.PlayerState

data class NoteDetailUiState(
    val noteId: Long? = null,
    val noteTitle: String = "New Note",
    val initialHtmlContentForEditor: String? = null,
    val displayedAudioBlocks: List<NoteBlock> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSaving: Boolean = false,
    val isRecording: Boolean = false,
    val isSpeechToTextActive: Boolean = false,

    val currentPlayingAudioBlockId: Long? = null,
    val audioPlayerState: PlayerState = PlayerState.IDLE,
    val currentAudioPositionMs: Int = 0,
    val currentAudioTotalDurationMs: Int = 0,

    val showPermissionRationaleDialog: Boolean = false,
    val permissionGranted: Boolean = false, // Разрешение на микрофон
    val isEditing: Boolean = false,

    // Новые поля для напоминаний
    val reminderAt: Long? = null,
    val showReminderDialog: Boolean = false,
    val notificationPermissionStatus: NotificationPermissionStatus = NotificationPermissionStatus.UNDETERMINED
)

enum class NotificationPermissionStatus {
    UNDETERMINED,
    GRANTED,
    DENIED
}