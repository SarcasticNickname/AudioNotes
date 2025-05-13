package com.myprojects.audionotes.ui.screens.archived // или com.myprojects.audionotes.ui.screens.common

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Импортируем все из filled
import androidx.compose.material.icons.outlined.* // Импортируем все из outlined
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteCategory
import com.myprojects.audionotes.ui.theme.AudioNotesTheme
import com.myprojects.audionotes.ui.viewmodel.ActiveFilters
import com.myprojects.audionotes.ui.viewmodel.ArchivedNotesViewModel
import com.myprojects.audionotes.ui.viewmodel.SortOption
import com.myprojects.audionotes.ui.viewmodel.TriStateFilterSelection
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_PREFIX
import com.myprojects.audionotes.util.AUDIO_PLACEHOLDER_SUFFIX
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*

// Вспомогательная функция для извлечения фрагмента текста из HTML
private fun extractContentSnippetArchived(htmlContent: String, maxLength: Int = 120): String {
    val plainTextWithPlaceholders = Jsoup.parse(htmlContent).text()
    val placeholderRegex = Regex(
        Regex.escape(AUDIO_PLACEHOLDER_PREFIX) + "\\s*\\d+\\s*" + Regex.escape(
            AUDIO_PLACEHOLDER_SUFFIX
        )
    )
    val cleanText = placeholderRegex.replace(plainTextWithPlaceholders, " ").trim()
    val condensedText = cleanText.replace(Regex("\\s+"), " ").trim()
    return if (condensedText.length > maxLength) condensedText.substring(0, maxLength)
        .trimEnd() + "..." else condensedText
}

// Форматеры дат (можно вынести в общий файл utils)
private val noteItemDateFormatterArchived =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
// reminderDateFormatterArchived не используется в ArchivedNoteItem, но может понадобиться

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedNotesScreen(
    navController: NavController,
    viewModel: ArchivedNotesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var noteToDeleteId by remember { mutableStateOf<Long?>(null) }

    var isSearchActive by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }

    val handleDeleteRequest = { noteId: Long ->
        noteToDeleteId = noteId
        showDeleteDialog = true
    }

    LaunchedEffect(
        uiState.searchQuery,
        uiState.currentSortOption,
        uiState.activeFilters.isAnyFilterApplied
    ) {
        if (uiState.error != null) {
            // viewModel.clearError() // Оставим кнопку Dismiss для ошибки
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Archived Notes") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back to Notes")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        // Кнопку "Архив" убираем с этого экрана
                        IconButton(onClick = { Log.d("ArchivedNotesScreen", "Settings clicked") }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                )
                // Переиспользуем ListActionsPanel, если она вынесена или определена здесь
                ArchivedListActionsPanel(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    isSearchActive = isSearchActive,
                    onToggleSearch = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive && uiState.searchQuery.isNotEmpty()) {
                            viewModel.setSearchQuery("")
                        }
                    },
                    currentSortOption = uiState.currentSortOption,
                    onFilterClick = { showFilterDialog = true },
                    onSortOptionSelected = viewModel::setSortOption,
                    isFilterActive = uiState.activeFilters.isAnyFilterApplied
                )
            }
        },
        floatingActionButton = { /* FAB здесь не нужен */ }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading && (uiState.notes.isEmpty() || uiState.searchQuery.isNotBlank() || uiState.currentSortOption != SortOption.DEFAULT || uiState.activeFilters.isAnyFilterApplied) -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Error",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Error: ${uiState.error}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.clearError() }) { Text("Dismiss") }
                    }
                }

                uiState.notes.isEmpty() && !uiState.isLoading -> {
                    Text(
                        text = if (uiState.searchQuery.isNotBlank() || uiState.currentSortOption != SortOption.DEFAULT || uiState.activeFilters.isAnyFilterApplied) "No archived notes match your criteria." else "Archive is empty.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }

                else -> {
                    ArchivedNoteListInternal( // Используем внутренний Composable
                        notes = uiState.notes,
                        onNoteClick = { noteId ->
                            navController.navigate(
                                com.myprojects.audionotes.ui.navigation.Screen.NoteDetail.createRoute(
                                    noteId
                                )
                            )
                        },
                        onDeleteRequest = handleDeleteRequest,
                        onUnarchiveRequest = { noteId -> viewModel.unarchiveNote(noteId) }
                    )
                }
            }
        }
    }

    if (showDeleteDialog && noteToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; noteToDeleteId = null },
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to permanently delete this archived note?") },
            confirmButton = {
                Button(
                    onClick = {
                        noteToDeleteId?.let {
                            viewModel.deleteArchivedNote(
                                it
                            )
                        }; showDeleteDialog = false; noteToDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = {
                    showDeleteDialog = false; noteToDeleteId = null
                }) { Text("Cancel") }
            }
        )
    }

    if (showFilterDialog) {
        ArchivedFilterDialog( // Используем внутренний Composable
            initialFilters = uiState.activeFilters,
            availableCategories = uiState.availableCategories,
            onDismiss = { showFilterDialog = false },
            onApplyFilters = { newFilters ->
                viewModel.applyFilters(newFilters)
                showFilterDialog = false
            },
            onResetFilters = { viewModel.resetFilters() }
        )
    }
}

// --- Панель действий (аналог ListActionsPanel, но можно назвать по-другому для ясности) ---
@Composable
fun ArchivedListActionsPanel(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
    currentSortOption: SortOption,
    onFilterClick: () -> Unit,
    onSortOptionSelected: (SortOption) -> Unit,
    isFilterActive: Boolean,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            delay(100); focusRequester.requestFocus()
        } else {
            focusManager.clearFocus(force = true); keyboardController?.hide()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = if (isSearchActive) Arrangement.Start else Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSearchActive) {
            IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.ArrowBack, "Close Search") }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(horizontal = 4.dp),
                placeholder = { Text("Search archived notes...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide(); focusManager.clearFocus() }),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                "Clear Search"
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        } else {
            ArchivedActionButton(
                text = "Search",
                icon = Icons.Filled.Search,
                onClick = onToggleSearch
            )
            ArchivedActionButton(
                text = "Filter",
                icon = if (isFilterActive) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
                onClick = onFilterClick,
                tint = if (isFilterActive) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
            Box {
                ArchivedActionButton(
                    text = "Sort",
                    icon = Icons.Filled.Sort,
                    onClick = { showSortMenu = true })
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortOption.allOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = { onSortOptionSelected(option); showSortMenu = false },
                            trailingIcon = if (option == currentSortOption) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 2.dp, vertical = 0.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = tint)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(ButtonDefaults.IconSize)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedFilterDialog(
    initialFilters: ActiveFilters,
    availableCategories: List<NoteCategory>,
    onDismiss: () -> Unit,
    onApplyFilters: (ActiveFilters) -> Unit,
    onResetFilters: () -> Unit
) {
    var tempSelectedCategories by remember { mutableStateOf(initialFilters.selectedCategories) }
    var tempReminderFilter by remember { mutableStateOf(initialFilters.reminderFilter) }
    var tempAudioFilter by remember { mutableStateOf(initialFilters.audioFilter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Archived Notes") }, // Title changed
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp) // Keep padding for content consistency
            ) {
                Text(
                    "Categories",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp, top = 8.dp)
                )
                availableCategories.forEach { category ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newSelection = tempSelectedCategories.toMutableSet()
                                if (category.name in newSelection) newSelection.remove(category.name)
                                else newSelection.add(category.name)
                                tempSelectedCategories = newSelection
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = category.name in tempSelectedCategories,
                            onCheckedChange = null // Controlled by Row click
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(category.color)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(category.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (availableCategories.isNotEmpty()) Divider(Modifier.padding(vertical = 10.dp))

                Text(
                    "Has Reminder",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(
                        bottom = 8.dp,
                        top = if (availableCategories.isEmpty()) 8.dp else 0.dp
                    )
                )
                // Assuming ArchivedTriStateSegmentedButton or a common one is available
                ArchivedTriStateSegmentedButton( // Or your common TriStateSegmentedButton
                    currentSelection = tempReminderFilter,
                    onSelectionChange = { tempReminderFilter = it }
                )
                Divider(Modifier.padding(vertical = 10.dp))

                Text(
                    "Has Audio",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // Assuming ArchivedTriStateSegmentedButton or a common one is available
                ArchivedTriStateSegmentedButton( // Or your common TriStateSegmentedButton
                    currentSelection = tempAudioFilter,
                    onSelectionChange = { tempAudioFilter = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApplyFilters(
                        ActiveFilters(
                            selectedCategories = tempSelectedCategories,
                            reminderFilter = tempReminderFilter,
                            audioFilter = tempAudioFilter
                        )
                    )
                }
            ) { Text("Apply") }
        },
        dismissButton = {
            Row { // This Row groups "Reset" and "Cancel"
                TextButton(
                    onClick = {
                        onResetFilters()
                        tempSelectedCategories = ActiveFilters.NONE.selectedCategories
                        tempReminderFilter = ActiveFilters.NONE.reminderFilter
                        tempAudioFilter = ActiveFilters.NONE.audioFilter
                    }
                ) { Text("Reset") }
                Spacer(Modifier.width(8.dp)) // Spacing between Reset and Cancel
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false) // Keep this if you need a wider dialog
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedTriStateSegmentedButton(
    currentSelection: TriStateFilterSelection,
    onSelectionChange: (TriStateFilterSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        TriStateFilterSelection.entries.forEachIndexed { index, selection ->
            SegmentedButton(
                selected = currentSelection == selection,
                onClick = { onSelectionChange(selection) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = TriStateFilterSelection.entries.size
                )
            ) { Text(selection.displayName) }
        }
    }
}

// --- Список и элемент списка для архивированных заметок ---
@Composable
fun ArchivedNoteListInternal(
    notes: List<Note>,
    onNoteClick: (Long) -> Unit,
    onDeleteRequest: (Long) -> Unit,
    onUnarchiveRequest: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(notes, key = { note -> "archived-${note.id}" }) { note ->
            ArchivedNoteItem(
                note = note,
                onClick = { onNoteClick(note.id) },
                onDeleteRequest = { onDeleteRequest(note.id) },
                onUnarchiveRequest = { onUnarchiveRequest(note.id) })
        }
    }
}

@Composable
fun ArchivedNoteItem(
    note: Note,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
    onUnarchiveRequest: () -> Unit
) {
    val category = remember(note.category) { NoteCategory.fromName(note.category) }
    val contentSnippet = remember(note.content) { extractContentSnippetArchived(note.content) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), // Чуть меньше elevation
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                1.dp
            )
        ) // Немного другой фон
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.title.ifEmpty { "Untitled Note" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Иконку активного напоминания здесь не показываем
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Unarchive") },
                            onClick = { onUnarchiveRequest(); showMenu = false },
                            leadingIcon = { Icon(Icons.Outlined.Unarchive, "Unarchive note") })
                        DropdownMenuItem(
                            text = { Text("Delete Permanently") },
                            onClick = { onDeleteRequest(); showMenu = false },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    "Delete permanently",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            })
                    }
                }
            }
            if (category != NoteCategory.NONE) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(category.color)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val showTopDivider =
                category != NoteCategory.NONE || contentSnippet.isNotBlank() // Показываем, если есть категория или сниппет
            if (showTopDivider) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    thickness = 0.5.dp
                )
            }
            if (contentSnippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = contentSnippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Второй разделитель не нужен, если даты идут сразу после основного
            val datesBlockTopSpacerHeight =
                if (showTopDivider && contentSnippet.isBlank()) 8.dp else if (!showTopDivider) 8.dp else 0.dp // Если есть верхний разделитель и нет сниппета, то отступ уже есть. Иначе, если не было верхнего разделителя, нужен отступ
            if (contentSnippet.isNotBlank() && showTopDivider) Spacer(modifier = Modifier.height(8.dp)) // Если и сниппет и верхний разделитель, то еще один отступ

            Column {
                // Для архива можно показывать дату последнего изменения (когда заархивировали)
                Text(
                    text = "Archived on: ${noteItemDateFormatterArchived.format(Date(note.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun ArchivedNotesScreenPreview() {
    AudioNotesTheme {
        val navController = rememberNavController()
        ArchivedNotesScreen(navController = navController)
    }
}