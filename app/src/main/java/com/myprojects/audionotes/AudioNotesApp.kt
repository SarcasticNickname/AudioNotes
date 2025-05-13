package com.myprojects.audionotes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AudioNotesApp : Application() {
    // Пока ничего не нужно, но аннотация важна для Hilt
}