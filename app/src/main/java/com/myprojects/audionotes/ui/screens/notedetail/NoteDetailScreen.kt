package com.myprojects.audionotes.ui.screens.notedetail

import android.Manifest
import android.os.Build
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Notifications // Для иконки неактивного напоминания
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.myprojects.audionotes.data.local.dao.cleanAudioPlaceholdersForRegex
import com.myprojects.audionotes.data.local.entity.NoteBlock
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_SUFFIX
import com.myprojects.audionotes.util.PlayerState
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// Эти определения должны быть здесь или импортированы, если они в отдельном файле utils.
sealed interface ViewContentPart
data class TextPart(val htmlSegment: String) : ViewContentPart
data class AudioPart(val block: NoteBlock) : ViewContentPart

fun parseHtmlContentForViewMode(
    htmlContent: String?,
    availableAudioBlocks: List<NoteBlock>
): List<ViewContentPart> {
    val workingHtml = (htmlContent ?: "<p><br></p>").cleanAudioPlaceholdersForRegex()
    val parts = mutableListOf<ViewContentPart>()
    val regex = Regex(
        Regex.escape(AUDIO_PLACEHOLDER_PREFIX) + "\\s*(\\d+)\\s*" + Regex.escape(
            AUDIO_PLACEHOLDER_SUFFIX
        )
    )
    var lastIndex = 0
    val matches = regex.findAll(workingHtml).toList()

    if (matches.isEmpty()) {
        if (workingHtml.isNotBlank() && workingHtml != "<p></p>" && workingHtml != "<p><br></p>" && workingHtml != "<p><br/></p>") {
            parts.add(TextPart(workingHtml))
        }
    } else {
        matches.forEach { matchResult ->
            val placeholderStart = matchResult.range.first
            val placeholderEnd = matchResult.range.last + 1
            if (placeholderStart > lastIndex) {
                val htmlSegment = workingHtml.substring(lastIndex, placeholderStart)
                if (htmlSegment.isNotBlank()) parts.add(TextPart(htmlSegment))
            }
            val audioBlockId = matchResult.groupValues[1].toLongOrNull()
            audioBlockId?.let { id ->
                availableAudioBlocks.find { it.id == id }
                    ?.let { block -> parts.add(AudioPart(block)) }
                    ?: run {
                        parts.add(TextPart(matchResult.value)); Log.w(
                        "ViewContentParser",
                        "Audio block for placeholder ${matchResult.value} not found."
                    )
                    }
            }
            lastIndex = placeholderEnd
        }
        if (lastIndex < workingHtml.length) {
            val htmlSegment = workingHtml.substring(lastIndex)
            if (htmlSegment.isNotBlank()) parts.add(TextPart(htmlSegment))
        }
    }
    return parts.filterNot { it is TextPart && (it.htmlSegment.isBlank() || it.htmlSegment == "<p></p>" || it.htmlSegment == "<p><br></p>" || it.htmlSegment == "<p><br/></p>") }
}

fun formatDuration(millis: Long?): String {
    if (millis == null || millis < 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

private val spanRegex = Regex("""<span\s+style="([^"]*)">(.*?)</span>""", RegexOption.IGNORE_CASE)
fun String.convertForHtmlCompat(): String = spanRegex.replace(this) { m ->
    val style = m.groupValues[1]
    val inner = m.groupValues[2]
    val colorHex =
        Regex("""color:\s*rgba?\((\d+),\s*(\d+),\s*(\d+)""").find(style)?.destructured?.let { (r, g, b) ->
            String.format(
                "#%02X%02X%02X",
                r.toInt(),
                g.toInt(),
                b.toInt()
            )
        }
    val colorOpen = colorHex?.let { """<font color="$it">""" } ?: ""
    val colorClose = if (colorHex != null) "</font>" else ""
    val sizePx = Regex("""font-size:\s*(\d+)""").find(style)?.groupValues?.get(1)?.toIntOrNull()
    val (sizeOpen, sizeClose) = when (sizePx) {
        in 0..13 -> "<small>" to "</small>"; in 19..Int.MAX_VALUE -> "<big>" to "</big>"; else -> "" to ""
    }
    "$colorOpen$sizeOpen$inner$sizeClose$colorClose"
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    viewModel: NoteDetailViewModel = hiltViewModel(),
    navController: NavController
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val richTextState = viewModel.richTextState

    val contentPartsForViewMode by remember(
        uiState.initialHtmlContentForEditor,
        uiState.displayedAudioBlocks,
        uiState.isEditing
    ) {
        derivedStateOf {
            if (!uiState.isEditing && uiState.initialHtmlContentForEditor != null) {
                parseHtmlContentForViewMode(
                    uiState.initialHtmlContentForEditor,
                    uiState.displayedAudioBlocks
                )
            } else {
                emptyList()
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = viewModel::onPermissionResult
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = viewModel::onNotificationPermissionResult
    )

    // Побочный эффект для запроса разрешения на уведомления
    LaunchedEffect(uiState.notificationPermissionStatus) {
        if (uiState.notificationPermissionStatus == NotificationPermissionStatus.DENIED) {
            // ViewModel установил статус DENIED, значит, нужно запросить разрешение
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.d("NoteDetailScreen", "Requesting notification permission from LaunchedEffect.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // Сбрасываем статус, чтобы не запрашивать повторно без действия пользователя
                viewModel.resetNotificationPermissionStatusRequest()
            }
        }
    }

    if (uiState.showReminderDialog) {
        ReminderDateTimePickerDialog(
            initialDateTimeMillis = uiState.reminderAt,
            onDateTimeSelected = { millis -> viewModel.onSetReminder(millis) },
            onDismiss = viewModel::onDismissReminderDialog,
            onClearReminder = viewModel::onClearReminder
        )
    }

    BackHandler(enabled = true) {
        if (uiState.isEditing) {
            if (uiState.isSpeechToTextActive) viewModel.handleSpeechToTextButtonPress()
            else if (viewModel.hasUnsavedChanges()) viewModel.saveNote(switchToViewModeAfterSave = true)
            else viewModel.toggleEditMode()
        } else {
            navController.popBackStack()
        }
    }

    val showFormattingPanel = uiState.isEditing
    val bodyLargeFontSizeSp = MaterialTheme.typography.bodyLarge.fontSize.value
    val onSurfaceColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (uiState.isEditing) BasicTextField(
                            value = uiState.noteTitle,
                            onValueChange = viewModel::updateNoteTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp),
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (uiState.noteTitle.isEmpty()) Text(
                                        "Note Title",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    )
                                    innerTextField()
                                }
                            }) else Text(
                            text = uiState.noteTitle.ifBlank { "Untitled Note" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (uiState.isEditing) {
                                if (uiState.isSpeechToTextActive) viewModel.handleSpeechToTextButtonPress()
                                else if (viewModel.hasUnsavedChanges()) viewModel.saveNote(true)
                                else viewModel.toggleEditMode()
                            } else navController.popBackStack()
                        }) { Icon(Icons.Filled.ArrowBack, "Go back") }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.onReminderIconClick() }) {
                            val reminderSetAndFuture =
                                uiState.reminderAt != null && (uiState.reminderAt
                                    ?: 0) > System.currentTimeMillis()
                            Icon(
                                imageVector = if (reminderSetAndFuture) Icons.Filled.NotificationsActive else Icons.Outlined.Notifications,
                                contentDescription = "Set Reminder",
                                tint = if (reminderSetAndFuture) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                        if (uiState.isEditing) {
                            IconButton(
                                onClick = { viewModel.saveNote(switchToViewModeAfterSave = true) },
                                enabled = !uiState.isSaving && viewModel.hasUnsavedChanges() && !uiState.isSpeechToTextActive && !uiState.isRecording
                            ) {
                                if (uiState.isSaving) CircularProgressIndicator(Modifier.size(24.dp))
                                else Icon(Icons.Filled.Done, "Save Note")
                            }
                        } else {
                            IconButton(onClick = { /* TODO: Confirm delete */ }) {
                                Icon(Icons.Filled.DeleteOutline, "Delete Note")
                            }
                            IconButton(onClick = { viewModel.toggleEditMode() }) {
                                Icon(Icons.Filled.Edit, "Edit Note")
                            }
                        }
                    }
                )
                AnimatedVisibility(
                    visible = showFormattingPanel,
                    enter = slideInVertically { fullHeight -> -fullHeight },
                    exit = slideOutVertically { fullHeight -> -fullHeight }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleBoldSelection() }) {
                            Icon(
                                Icons.Filled.FormatBold,
                                "Bold"
                            )
                        }
                        IconButton(onClick = { viewModel.toggleItalicSelection() }) {
                            Icon(
                                Icons.Filled.FormatItalic,
                                "Italic"
                            )
                        }
                        IconButton(onClick = { viewModel.toggleUnderlineSelection() }) {
                            Icon(
                                Icons.Filled.FormatUnderlined,
                                "Underline"
                            )
                        }
                        IconButton(onClick = { viewModel.toggleStrikethroughSelection() }) {
                            Icon(
                                Icons.Filled.FormatStrikethrough,
                                "Strikethrough"
                            )
                        }
                        VerticalDivider()
                        IconButton(onClick = { viewModel.changeTextColor(Color.Unspecified) }) {
                            Icon(
                                Icons.Filled.FormatColorReset,
                                "Default Color"
                            )
                        }
                        IconButton(onClick = { viewModel.changeTextColor(Color.Red) }) {
                            Icon(
                                Icons.Outlined.Circle,
                                "Red Text",
                                tint = Color.Red
                            )
                        }
                        IconButton(onClick = { viewModel.changeTextColor(Color.Blue) }) {
                            Icon(
                                Icons.Outlined.Circle,
                                "Blue Text",
                                tint = Color.Blue
                            )
                        }
                        IconButton(onClick = { viewModel.changeTextColor(Color(0xFF006400)) }) {
                            Icon(
                                Icons.Outlined.Circle,
                                "Green Text",
                                tint = Color(0xFF006400)
                            )
                        }
                        VerticalDivider()
                        IconButton(onClick = { viewModel.setFontSize(12.sp) }) {
                            Text(
                                "S",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { viewModel.setFontSize(16.sp) }) {
                            Text(
                                "M",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { viewModel.setFontSize(20.sp) }) {
                            Text(
                                "L",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (uiState.isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FloatingActionButton(
                        onClick = {
                            if (!uiState.isRecording) {
                                if (uiState.permissionGranted) viewModel.handleSpeechToTextButtonPress()
                                else recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        containerColor = if (uiState.isSpeechToTextActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            if (uiState.isSpeechToTextActive) Icons.Filled.StopCircle else Icons.Filled.RecordVoiceOver,
                            "STT"
                        )
                    }
                    FloatingActionButton(
                        onClick = {
                            if (!uiState.isSpeechToTextActive && !uiState.isSaving) viewModel.handleRecordButtonPress()
                        },
                        containerColor = if (uiState.isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            if (uiState.isRecording) Icons.Filled.StopCircle else Icons.Filled.Mic,
                            "Record"
                        )
                    }
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
            if (uiState.isLoading && (uiState.noteId == null || uiState.noteId == -1L || (uiState.noteId != 0L && uiState.initialHtmlContentForEditor == null))) {
                item {
                    Box(
                        Modifier.fillParentMaxSize(),
                        Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            } else {
                uiState.error?.let {
                    item {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (uiState.isEditing) {
                    item {
                        RichTextEditor(
                            state = richTextState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 200.dp),
                            colors = RichTextEditorDefaults.richTextEditorColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            placeholder = { Text("Write your note here...") }
                        )
                    }
                    if (uiState.displayedAudioBlocks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            Text("Attached Audio:", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        items(
                            items = uiState.displayedAudioBlocks,
                            key = { block -> "edit-${block.id}" }) { block ->
                            AudioPlayerItem(
                                block,
                                uiState.currentPlayingAudioBlockId == block.id && uiState.audioPlayerState == PlayerState.PLAYING,
                                uiState.currentPlayingAudioBlockId == block.id && uiState.audioPlayerState == PlayerState.PAUSED,
                                if (uiState.currentPlayingAudioBlockId == block.id) uiState.currentAudioPositionMs else 0,
                                (block.audioDurationMs ?: 0L).toInt(),
                                { viewModel.playAudio(block.id) },
                                { viewModel.deleteAudioBlockFromPlayer(block.id) },
                                { progress ->
                                    viewModel.onSeekAudio(
                                        block.id,
                                        (progress * (block.audioDurationMs ?: 0L)).roundToInt()
                                    )
                                },
                                true
                            )
                        }
                    }
                } else { // View mode
                    if (contentPartsForViewMode.isEmpty() && (uiState.initialHtmlContentForEditor.isNullOrBlank() || uiState.initialHtmlContentForEditor == "<p></p>" || uiState.initialHtmlContentForEditor == "<p><br></p>" || uiState.initialHtmlContentForEditor == "<p><br/></p>")) {
                        item {
                            Box(
                                Modifier.fillParentMaxSize(),
                                Alignment.Center
                            ) { Text("Note is empty.", style = MaterialTheme.typography.bodyLarge) }
                        }
                    } else {
                        itemsIndexed(contentPartsForViewMode, key = { _, part ->
                            when (part) {
                                is TextPart -> "view-text-${part.htmlSegment.hashCode()}"; is AudioPart -> "view-audio-${part.block.id}"
                            }
                        }) { _, part ->
                            when (part) {
                                is TextPart -> AndroidView(
                                    factory = { ctx ->
                                        TextView(ctx).apply {
                                            textSize = bodyLargeFontSizeSp; setTextColor(
                                            onSurfaceColorArgb
                                        )
                                            movementMethod =
                                                LinkMovementMethod.getInstance(); isClickable =
                                            false; isFocusable = false
                                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        }
                                    },
                                    update = { tv ->
                                        tv.text = HtmlCompat.fromHtml(
                                            part.htmlSegment.convertForHtmlCompat(),
                                            HtmlCompat.FROM_HTML_MODE_LEGACY
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                )

                                is AudioPart -> AudioPlayerItem(
                                    part.block,
                                    uiState.currentPlayingAudioBlockId == part.block.id && uiState.audioPlayerState == PlayerState.PLAYING,
                                    uiState.currentPlayingAudioBlockId == part.block.id && uiState.audioPlayerState == PlayerState.PAUSED,
                                    if (uiState.currentPlayingAudioBlockId == part.block.id) uiState.currentAudioPositionMs else 0,
                                    (part.block.audioDurationMs ?: 0L).toInt(),
                                    { viewModel.playAudio(part.block.id) }, {},
                                    { progress ->
                                        viewModel.onSeekAudio(
                                            part.block.id,
                                            (progress * (part.block.audioDurationMs
                                                ?: 0L)).roundToInt()
                                        )
                                    },
                                    false
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uiState.showPermissionRationaleDialog && uiState.isEditing) {
            AlertDialog(
                onDismissRequest = viewModel::onPermissionRationaleDismissed,
                title = { Text("Permission Required") },
                text = { Text("Microphone access is needed for audio features.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.onPermissionRationaleDismissed(); recordAudioPermissionLauncher.launch(
                        Manifest.permission.RECORD_AUDIO
                    )
                    }) { Text("Grant") }
                },
                dismissButton = {
                    Button(onClick = viewModel::onPermissionRationaleDismissed) {
                        Text(
                            "Cancel"
                        )
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDateTimePickerDialog(
    initialDateTimeMillis: Long?,
    onDateTimeSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    onClearReminder: () -> Unit
) {
    val context = LocalContext.current
    val calendar = remember { Calendar.getInstance() }
    val currentSystemTime = System.currentTimeMillis()

    // Инициализация календаря для выбора:
    // Если есть начальное время и оно в будущем, используем его.
    // Иначе, используем текущее время + 5 минут (или другое значение по умолчанию).
    val initialPickerTime =
        if (initialDateTimeMillis != null && initialDateTimeMillis > currentSystemTime) {
            initialDateTimeMillis
        } else {
            currentSystemTime + TimeUnit.MINUTES.toMillis(5)
        }
    calendar.timeInMillis = initialPickerTime

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = calendar.timeInMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                return utcTimeMillis >= todayStart
            }
        }
    )
    val timePickerState = rememberTimePickerState(
        initialHour = calendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = calendar.get(Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    )

    var showDatePickerDialog by remember { mutableStateOf(true) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    val tempSelectedCalendar =
        remember { Calendar.getInstance().apply { timeInMillis = initialPickerTime } }

    val simpleDateFormat =
        remember { SimpleDateFormat("EEE, d MMM yyyy, HH:mm", Locale.getDefault()) }
    val currentReminderText = initialDateTimeMillis?.let {
        if (it > currentSystemTime) "Текущее: ${simpleDateFormat.format(Date(it))}"
        else if (it > 0) "Напоминание в прошлом" else null
    }

    if (showDatePickerDialog) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        tempSelectedCalendar.timeInMillis = it
                    }
                    showDatePickerDialog = false; showTimePickerDialog = true
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePickerDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = { Text("Выберите время", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    currentReminderText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    TimePicker(state = timePickerState, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tempSelectedCalendar.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    tempSelectedCalendar.set(Calendar.MINUTE, timePickerState.minute)
                    tempSelectedCalendar.set(
                        Calendar.SECOND,
                        0
                    ); tempSelectedCalendar.set(Calendar.MILLISECOND, 0)
                    if (tempSelectedCalendar.timeInMillis <= System.currentTimeMillis()) {
                        // TODO: Показать Snackbar или сообщение об ошибке, что время должно быть в будущем
                        Log.w("ReminderDialog", "Selected time is in the past or current.")
                        return@TextButton
                    }
                    onDateTimeSelected(tempSelectedCalendar.timeInMillis)
                }) { Text("Установить") }
            },
            dismissButton = {
                Row {
                    if (initialDateTimeMillis != null && initialDateTimeMillis > 0) {
                        TextButton(onClick = { onClearReminder() }) { Text("Удалить") }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                }
            }
        )
    }
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Divider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
        modifier = modifier
            .height(24.dp)
            .width(1.dp)
    )
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
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Audio Clip (ID: ${audioBlock.id % 1000})",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatDuration(currentPositionMs.toLong())} / ${
                                formatDuration(
                                    totalDurationMs.toLong()
                                )
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPauseClick) {
                        Icon(
                            if (isPlaying) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
                            if (isPlaying) "Pause" else "Play",
                            Modifier.size(40.dp)
                        )
                    }
                    if (showDeleteButton) {
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                "Delete audio clip",
                                Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            if (totalDurationMs > 0) {
                var sliderValue by remember(
                    audioBlock.id,
                    currentPositionMs,
                    totalDurationMs,
                    isPlaying,
                    isPaused
                ) {
                    mutableStateOf(if (totalDurationMs > 0) currentPositionMs.toFloat() / totalDurationMs.toFloat() else 0f)
                }
                var isUserSeeking by remember { mutableStateOf(false) }
                Slider(
                    value = sliderValue.coerceIn(0f, 1f),
                    onValueChange = { newValue -> isUserSeeking = true; sliderValue = newValue },
                    onValueChangeFinished = { onSeek(sliderValue); isUserSeeking = false },
                    modifier = Modifier.fillMaxWidth()
                )
                LaunchedEffect(
                    currentPositionMs,
                    totalDurationMs,
                    audioBlock.id,
                    isPlaying,
                    isPaused
                ) {
                    if (!isUserSeeking) {
                        sliderValue =
                            if (totalDurationMs > 0) (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(
                                0f,
                                1f
                            ) else 0f
                    }
                }
            }
        }
    }
}