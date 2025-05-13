// Файл: com/myprojects/audionotes/data/preferences/UserPreferencesRepository.kt
package com.myprojects.audionotes.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Создаем DataStore instance
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

object PreferenceKeys {
    val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
}

// Enum для поддерживаемых языков
enum class SpeechLanguage(val languageTag: String, val displayName: String) {
    RUSSIAN("ru-RU", "Русский"),
    ENGLISH("en-US", "English (US)"); // Можно добавить другие варианты английского или другие языки

    companion object {
        fun fromTag(tag: String?): SpeechLanguage {
            return entries.find { it.languageTag == tag } ?: RUSSIAN // По умолчанию русский
        }

        val defaultLanguage: SpeechLanguage get() = RUSSIAN
    }
}

@Singleton
class UserPreferencesRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val speechLanguageFlow: Flow<SpeechLanguage> = context.dataStore.data
        .map { preferences ->
            val langTag = preferences[PreferenceKeys.SPEECH_LANGUAGE]
                ?: SpeechLanguage.defaultLanguage.languageTag
            SpeechLanguage.fromTag(langTag)
        }

    suspend fun updateSpeechLanguage(language: SpeechLanguage) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SPEECH_LANGUAGE] = language.languageTag
        }
    }
}