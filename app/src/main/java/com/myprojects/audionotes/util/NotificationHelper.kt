package com.myprojects.audionotes.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.myprojects.audionotes.MainActivity
import com.myprojects.audionotes.R // Убедись, что есть иконка в res/drawable

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "audio_notes_reminder_channel"
        private const val CHANNEL_NAME = "AudioNotes Reminders"
        private const val CHANNEL_DESCRIPTION = "Notifications for audio note reminders"
        const val NAVIGATE_TO_NOTE_ID_EXTRA = "NAVIGATE_TO_NOTE_ID"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showReminderNotification(noteId: Long, noteTitle: String) {
        val resultIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NAVIGATE_TO_NOTE_ID_EXTRA, noteId)
        }

        val resultPendingIntent: PendingIntent? = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(resultIntent)
            getPendingIntent(noteId.toInt(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification) // ЗАМЕНИ НА СВОЮ ИКОНКУ
            .setContentTitle("Напоминание: $noteTitle")
            .setContentText("Пора взглянуть на вашу заметку.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(resultPendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(noteId.toInt(), builder.build())
    }
}