package com.myprojects.audionotes.ui.viewmodel

// import com.google.firebase.firestore.ktx.toObject // toObject уже не нужен, если мапим вручную
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.myprojects.audionotes.R
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteCategory
import com.myprojects.audionotes.data.preferences.SpeechLanguage
import com.myprojects.audionotes.data.preferences.UserPreferencesRepository
import com.myprojects.audionotes.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UserInfo(
    val uid: String,
    val displayName: String?,
    val email: String?
)

data class SettingsUiState(
    val currentUser: UserInfo? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSigningIn: Boolean = false,
    val lastBackupTimestamp: Long? = null,
    // МОЙ КОММЕНТАРИЙ: Добавлено поле для выбранного языка
    val selectedSpeechLanguage: SpeechLanguage = SpeechLanguage.defaultLanguage
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val firebaseAuth: FirebaseAuth,
    private val noteRepository: NoteRepository,
    private val firestore: FirebaseFirestore,
    // МОЙ КОММЕНТАРИЙ: Внедрен UserPreferencesRepository
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    // МОЙ КОММЕНТАРИЙ: _uiState теперь _internalUiState для комбинирования с потоком языка
    private val _internalUiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = combine(
        _internalUiState,
        userPreferencesRepository.speechLanguageFlow
    ) { internalState, speechLanguage ->
        internalState.copy(selectedSpeechLanguage = speechLanguage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState() // Начальное значение, которое будет немедленно обновлено
    )


    private val oneTapClient: SignInClient by lazy { Identity.getSignInClient(appContext) }
    private val signInRequest: BeginSignInRequest by lazy {
        BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(appContext.getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false) // Позволяет выбирать любой аккаунт Google
                    .build()
            )
            .setAutoSelectEnabled(false) // Не выбирать автоматически, дать пользователю выбор
            .build()
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val USERS_COLLECTION = "users"
        private const val NOTES_COLLECTION = "notes"
        private const val LAST_BACKUP_FIELD = "lastBackupTimestamp"
    }

    init {
        checkCurrentUser()
        // Загрузка языка теперь происходит через combine в объявлении uiState
    }

    private fun checkCurrentUser() {
        val firebaseUser = firebaseAuth.currentUser
        // МОЙ КОММЕНТАРИЙ: isLoading и currentUser обновляются в _internalUiState
        _internalUiState.update {
            it.copy(currentUser = firebaseUser?.toUserInfo(), isLoading = false)
        }
        if (firebaseUser != null) {
            loadLastBackupTimestamp(firebaseUser.uid)
        }
        Log.d(TAG, "Current Firebase user: ${firebaseUser?.uid}")
    }

    fun initiateSignIn(launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>) {
        // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
        _internalUiState.update { it.copy(isSigningIn = true, error = null, isLoading = true) }
        viewModelScope.launch {
            try {
                val result = oneTapClient.beginSignIn(signInRequest).await()
                val intentSenderRequest =
                    IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                launcher.launch(intentSenderRequest)
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In begin failed", e)
                _internalUiState.update { // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                    it.copy(
                        isSigningIn = false,
                        isLoading = false,
                        error = "Sign-in initialization failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun handleSignInResult(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            try {
                val credential = oneTapClient.getSignInCredentialFromIntent(activityResult.data)
                val googleIdToken = credential.googleIdToken
                if (googleIdToken != null) {
                    signInToFirebaseWithGoogleToken(googleIdToken)
                } else {
                    Log.e(TAG, "Google ID Token is null after successful One Tap.")
                    _internalUiState.update { // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                        it.copy(
                            isSigningIn = false,
                            isLoading = false,
                            error = "Failed to get Google ID Token."
                        )
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google Sign-In failed from Intent: ${e.statusCode}", e)
                _internalUiState.update { // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                    it.copy(
                        isSigningIn = false,
                        isLoading = false,
                        error = "Sign-in attempt failed: ${e.localizedMessage}"
                    )
                }
            }
        } else {
            Log.w(TAG, "Google Sign-in result was not OK. ResultCode: ${activityResult.resultCode}")
            _internalUiState.update { // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                it.copy(
                    isSigningIn = false,
                    isLoading = false,
                    error = "Sign-in cancelled or failed."
                )
            }
        }
    }

    private fun signInToFirebaseWithGoogleToken(idToken: String) {
        viewModelScope.launch {
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                firebaseAuth.signInWithCredential(credential).await()
                val firebaseUser = firebaseAuth.currentUser
                _internalUiState.update { // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                    it.copy(
                        currentUser = firebaseUser?.toUserInfo(),
                        isSigningIn = false,
                        isLoading = false,
                        error = null
                    )
                }
                firebaseUser?.uid?.let { loadLastBackupTimestamp(it) }
                Log.i(TAG, "Successfully signed in with Firebase: ${firebaseUser?.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Sign-In with Google Token failed", e)
                firebaseAuth.signOut()
                _internalUiState.update { // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                    it.copy(
                        currentUser = null,
                        isSigningIn = false,
                        isLoading = false,
                        error = "Firebase sign-in failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _internalUiState.update {
                it.copy(
                    isSigningIn = true,
                    isLoading = true
                )
            } // МОЙ КОММЕНТАРИЙ
            try {
                oneTapClient.signOut().await()
                firebaseAuth.signOut()
                // МОЙ КОММЕНТАРИЙ: Сбрасываем _internalUiState, selectedSpeechLanguage придет из combine
                _internalUiState.update {
                    SettingsUiState(
                        isLoading = false,
                        lastBackupTimestamp = null
                    )
                }
                Log.i(TAG, "User signed out successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Sign out failed", e)
                // МОЙ КОММЕНТАРИЙ: Обновляем _internalUiState
                _internalUiState.update {
                    SettingsUiState( // Пересоздаем с учетом возможного currentUser
                        currentUser = firebaseAuth.currentUser?.toUserInfo(),
                        isLoading = false,
                        error = "Sign out failed: ${e.localizedMessage}"
                        // selectedSpeechLanguage придет из combine
                    )
                }
            }
        }
    }

    fun backupNotesToFirestore() {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                it.copy(
                    error = "User not signed in. Cannot backup.",
                    isLoading = false
                )
            }
            return
        }
        _internalUiState.update { it.copy(isLoading = true, error = null) } // МОЙ КОММЕНТАРИЙ

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localNotes = noteRepository.getAllNotesForBackup()
                val userNotesCollection = firestore.collection(USERS_COLLECTION).document(userId)
                    .collection(NOTES_COLLECTION)

                val oldNotesSnapshot = userNotesCollection.get().await()
                if (!oldNotesSnapshot.isEmpty) {
                    val deleteBatch = firestore.batch()
                    oldNotesSnapshot.documents.forEach { doc -> deleteBatch.delete(doc.reference) }
                    deleteBatch.commit().await()
                    Log.i(TAG, "Old notes deleted from Firestore for user $userId before backup.")
                }

                if (localNotes.isEmpty()) {
                    Log.i(TAG, "No local notes to backup for user $userId.")
                    updateLastBackupTimestampInFirestore(
                        userId,
                        System.currentTimeMillis()
                    )
                    withContext(Dispatchers.Main) {
                        _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                            it.copy(
                                isLoading = false,
                                error = null,
                                lastBackupTimestamp = System.currentTimeMillis()
                            )
                        }
                    }
                    return@launch
                }

                val batch = firestore.batch()
                localNotes.forEach { note ->
                    val noteDocumentRef = userNotesCollection.document(note.id.toString())
                    val noteData = mapOf(
                        "title" to note.title, "content" to note.content,
                        "createdAt" to note.createdAt, "updatedAt" to note.updatedAt,
                        "reminderAt" to note.reminderAt, "category" to note.category,
                        "isArchived" to note.isArchived, "localId" to note.id
                    )
                    batch.set(noteDocumentRef, noteData)
                }
                batch.commit().await()

                val backupTime = System.currentTimeMillis()
                updateLastBackupTimestampInFirestore(userId, backupTime)
                withContext(Dispatchers.Main) {
                    _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                        it.copy(
                            isLoading = false,
                            error = null,
                            lastBackupTimestamp = backupTime
                        )
                    }
                }
                Log.i(
                    TAG,
                    "${localNotes.size} notes backed up successfully to Firestore for user $userId."
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error backing up notes to Firestore", e)
                withContext(Dispatchers.Main) {
                    _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                        it.copy(
                            isLoading = false,
                            error = "Backup failed: ${e.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    fun restoreNotesFromFirestore() {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                it.copy(
                    error = "User not signed in. Cannot restore.",
                    isLoading = false
                )
            }
            return
        }
        _internalUiState.update { it.copy(isLoading = true, error = null) } // МОЙ КОММЕНТАРИЙ

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userNotesCollection = firestore.collection(USERS_COLLECTION).document(userId)
                    .collection(NOTES_COLLECTION)
                val snapshot = userNotesCollection.get().await()

                if (snapshot.isEmpty) {
                    withContext(Dispatchers.Main) {
                        _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                            it.copy(
                                isLoading = false,
                                error = "No notes found in cloud backup."
                            )
                        }
                    }
                    return@launch
                }

                val notesFromFirestore = snapshot.documents.mapNotNull { doc ->
                    Note(
                        id = doc.id.toLongOrNull() ?: doc.getLong("localId") ?: 0L,
                        title = doc.getString("title") ?: "",
                        content = doc.getString("content") ?: "",
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis(),
                        reminderAt = doc.getLong("reminderAt"),
                        category = doc.getString("category") ?: NoteCategory.NONE.name,
                        isArchived = doc.getBoolean("isArchived") ?: false
                    )
                }
                noteRepository.replaceAllNotesFromBackup(notesFromFirestore)

                withContext(Dispatchers.Main) {
                    _internalUiState.update {
                        it.copy(
                            isLoading = false,
                            error = null
                        )
                    } // МОЙ КОММЕНТАРИЙ
                }
                Log.i(
                    TAG,
                    "${notesFromFirestore.size} notes restored successfully from Firestore for user $userId."
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring notes from Firestore", e)
                withContext(Dispatchers.Main) {
                    _internalUiState.update { // МОЙ КОММЕНТАРИЙ
                        it.copy(
                            isLoading = false,
                            error = "Restore failed: ${e.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun updateLastBackupTimestampInFirestore(userId: String, timestamp: Long) {
        try {
            val userDocRef = firestore.collection(USERS_COLLECTION).document(userId)
            userDocRef.set(mapOf(LAST_BACKUP_FIELD to timestamp), SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update last backup timestamp in Firestore", e)
        }
    }

    private fun loadLastBackupTimestamp(userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userDocRef = firestore.collection(USERS_COLLECTION).document(userId)
                val snapshot = userDocRef.get().await()
                val timestamp = snapshot.getLong(LAST_BACKUP_FIELD)
                withContext(Dispatchers.Main) {
                    _internalUiState.update { it.copy(lastBackupTimestamp = timestamp) }
                }
                if (timestamp != null) Log.i(TAG, "Last backup timestamp loaded: $timestamp")
                else Log.i(TAG, "No last backup timestamp found for user $userId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load last backup timestamp", e)
                withContext(Dispatchers.Main) {
                    _internalUiState.update { it.copy(lastBackupTimestamp = null) }
                }
            }
        }
    }

    fun clearError() {
        _internalUiState.update { it.copy(error = null) }
    }

    // МОЯ ФУНКЦИЯ: Новый метод для обновления языка в DataStore
    fun updateSpeechLanguage(language: SpeechLanguage) {
        viewModelScope.launch {
            userPreferencesRepository.updateSpeechLanguage(language)
            // _internalUiState.update { it.copy(selectedSpeechLanguage = language) } // Это уже не нужно, т.к. uiState собирается через combine
            Log.d(TAG, "Speech language updated to: ${language.displayName}")
        }
    }
}

fun FirebaseUser.toUserInfo(): UserInfo {
    return UserInfo(
        uid = this.uid,
        displayName = this.displayName,
        email = this.email
    )
}