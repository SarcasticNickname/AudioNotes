package com.myprojects.audionotes.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myprojects.audionotes.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_NOTE_ID = "note_id"
        const val KEY_NOTE_TITLE = "note_title"
        private const val TAG = "ReminderWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        val noteTitle = inputData.getString(KEY_NOTE_TITLE)

        Log.d(TAG, "Worker started for noteId: $noteId, title: $noteTitle")

        if (noteId == -1L || noteTitle == null) {
            Log.e(TAG, "Invalid input data. noteId: $noteId, noteTitle: $noteTitle")
            return@withContext Result.failure()
        }

        try {
            NotificationHelper(applicationContext).showReminderNotification(noteId, noteTitle)
            Log.i(TAG, "Notification shown for noteId: $noteId")
            return@withContext Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in ReminderWorker for noteId: $noteId", e)
            return@withContext Result.failure()
        }
    }
}