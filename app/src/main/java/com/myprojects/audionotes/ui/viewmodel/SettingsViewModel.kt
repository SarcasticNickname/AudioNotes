package com.myprojects.audionotes.ui.viewmodel

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
import com.google.firebase.firestore.ktx.toObject
import com.myprojects.audionotes.R // Для R.string.default_web_client_id
import com.myprojects.audionotes.data.local.entity.Note
import com.myprojects.audionotes.data.local.entity.NoteCategory
import com.myprojects.audionotes.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Data class для информации о пользователе, отображаемой в UI
data class UserInfo(
    val uid: String,
    val displayName: String?,
    val email: String?
)

// Состояние UI для экрана настроек
data class SettingsUiState(
    val currentUser: UserInfo? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSigningIn: Boolean = false,
    val lastBackupTimestamp: Long? = null // Время последнего успешного бэкапа
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val firebaseAuth: FirebaseAuth,
    private val noteRepository: NoteRepository, // Репозиторий для доступа к локальным заметкам
    private val firestore: FirebaseFirestore   // Firestore для облачного хранения
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val oneTapClient: SignInClient by lazy { Identity.getSignInClient(appContext) }
    private val signInRequest: BeginSignInRequest by lazy {
        BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(appContext.getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .setAutoSelectEnabled(false)
            .build()
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val USERS_COLLECTION = "users"
        private const val NOTES_COLLECTION = "notes"
        private const val LAST_BACKUP_FIELD = "lastBackupTimestamp"
    }

    init {
        checkCurrentUser() // Проверяем, вошел ли пользователь при запуске ViewModel
    }

    private fun checkCurrentUser() {
        val firebaseUser = firebaseAuth.currentUser
        _uiState.update {
            it.copy(currentUser = firebaseUser?.toUserInfo(), isLoading = false)
        }
        if (firebaseUser != null) {
            loadLastBackupTimestamp(firebaseUser.uid) // Загружаем время последнего бэкапа, если пользователь вошел
        }
        Log.d(TAG, "Current Firebase user: ${firebaseUser?.uid}")
    }

    fun initiateSignIn(launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>) {
        _uiState.update { it.copy(isSigningIn = true, error = null, isLoading = true) }
        viewModelScope.launch {
            try {
                val result = oneTapClient.beginSignIn(signInRequest).await()
                val intentSenderRequest =
                    IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                launcher.launch(intentSenderRequest)
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In begin failed", e)
                _uiState.update {
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
                    _uiState.update {
                        it.copy(
                            isSigningIn = false,
                            isLoading = false,
                            error = "Failed to get Google ID Token."
                        )
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google Sign-In failed from Intent: ${e.statusCode}", e)
                _uiState.update {
                    it.copy(
                        isSigningIn = false,
                        isLoading = false,
                        error = "Sign-in attempt failed: ${e.localizedMessage}"
                    )
                }
            }
        } else {
            Log.w(TAG, "Google Sign-in result was not OK. ResultCode: ${activityResult.resultCode}")
            _uiState.update {
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
                _uiState.update {
                    it.copy(
                        currentUser = firebaseUser?.toUserInfo(),
                        isSigningIn = false,
                        isLoading = false,
                        error = null
                    )
                }
                firebaseUser?.uid?.let { loadLastBackupTimestamp(it) } // Загружаем инфо о бэкапе
                Log.i(TAG, "Successfully signed in with Firebase: ${firebaseUser?.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Sign-In with Google Token failed", e)
                firebaseAuth.signOut() // Важно выйти, если что-то пошло не так с Firebase после получения токена Google
                _uiState.update {
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
            _uiState.update { it.copy(isSigningIn = true, isLoading = true) }
            try {
                oneTapClient.signOut().await()
                firebaseAuth.signOut()
                // Сбрасываем все состояние, включая время последнего бэкапа
                _uiState.update { SettingsUiState(isLoading = false, lastBackupTimestamp = null) }
                Log.i(TAG, "User signed out successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Sign out failed", e)
                // Даже если OneTap signOut не удался, Firebase signOut должен был сработать
                _uiState.update {
                    SettingsUiState(
                        currentUser = firebaseAuth.currentUser?.toUserInfo(),
                        isLoading = false,
                        error = "Sign out failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun backupNotesToFirestore() {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            _uiState.update {
                it.copy(
                    error = "User not signed in. Cannot backup.",
                    isLoading = false
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localNotes = noteRepository.getAllNotesForBackup()
                val userNotesCollection = firestore.collection(USERS_COLLECTION).document(userId)
                    .collection(NOTES_COLLECTION)

                // Очищаем старые заметки пользователя в облаке перед записью новых
                // Это стратегия "полного бэкапа", когда локальные данные - источник истины.
                // Если нужна синхронизация, логика будет сложнее.
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
                    ) // Обновляем время, даже если бэкап пуст
                    withContext(Dispatchers.Main) {
                        _uiState.update {
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
                    // Конвертируем Note в Map, как мы делали раньше
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
                withContext(Dispatchers.Main) { // Обновляем UI в главном потоке
                    _uiState.update {
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
                    _uiState.update {
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
            _uiState.update {
                it.copy(
                    error = "User not signed in. Cannot restore.",
                    isLoading = false
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userNotesCollection = firestore.collection(USERS_COLLECTION).document(userId)
                    .collection(NOTES_COLLECTION)
                val snapshot = userNotesCollection.get().await()

                if (snapshot.isEmpty) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "No notes found in cloud backup."
                            )
                        }
                    }
                    // Очищаем локальные данные, если бэкап пуст и это "полное восстановление"
                    // noteRepository.deleteAllNotes() // Нужен такой метод в DAO/Repo
                    // Пока что не удаляем локальные, если бэкап пуст
                    return@launch
                }

                val notesFromFirestore = snapshot.documents.mapNotNull { doc ->
                    // Firestore может возвращать Long для чисел, Room ожидает Long.
                    // Поле id в Note - val, поэтому при восстановлении оно будет установлено.
                    // Если localId отличается от Firestore doc.id, нужно решить, какой использовать.
                    // Здесь предполагаем, что doc.id и есть наш localId (как мы сохраняли).
                    Note(
                        id = doc.id.toLongOrNull() ?: doc.getLong("localId")
                        ?: 0L, // Используем Firestore ID или сохраненный localId
                        title = doc.getString("title") ?: "",
                        content = doc.getString("content") ?: "",
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis(),
                        reminderAt = doc.getLong("reminderAt"),
                        category = doc.getString("category") ?: NoteCategory.NONE.name,
                        isArchived = doc.getBoolean("isArchived") ?: false
                    )
                }
                // Перед восстановлением, удаляем все существующие локальные заметки,
                // чтобы избежать дубликатов и обеспечить "чистое" восстановление.
                // Это потребует метода `deleteAllNotes()` в DAO/Repository.
                // Пока что используем `replaceAllNotesFromBackup` который делает insertOrReplace
                noteRepository.replaceAllNotesFromBackup(notesFromFirestore)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, error = null) }
                }
                Log.i(
                    TAG,
                    "${notesFromFirestore.size} notes restored successfully from Firestore for user $userId."
                )
                // Обновление списков на других экранах произойдет автоматически через Room Flows.

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring notes from Firestore", e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
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
        // Этот метод вызывается из IO потока (backupNotesToFirestore)
        try {
            val userDocRef = firestore.collection(USERS_COLLECTION).document(userId)
            // Используем SetOptions.merge() чтобы обновить только это поле, не затирая другие данные пользователя, если они есть
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
                withContext(Dispatchers.Main) { // Обновляем UI в главном потоке
                    _uiState.update { it.copy(lastBackupTimestamp = timestamp) }
                }
                if (timestamp != null) Log.i(TAG, "Last backup timestamp loaded: $timestamp")
                else Log.i(TAG, "No last backup timestamp found for user $userId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load last backup timestamp", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(lastBackupTimestamp = null) } // Сбрасываем, если ошибка
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

// Вспомогательная функция для конвертации FirebaseUser в наш UserInfo data class
fun FirebaseUser.toUserInfo(): UserInfo {
    return UserInfo(
        uid = this.uid,
        displayName = this.displayName,
        email = this.email
    )
}