package dev.wplayer.app.data.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.wplayer.app.R

/** The notification channel for AI work (model downloads, subtitle making); returns its id. */
internal fun ensureAiChannel(context: Context): String {
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (notifications.getNotificationChannel(AI_CHANNEL_ID) == null) {
        notifications.createNotificationChannel(
            NotificationChannel(AI_CHANNEL_ID, context.getString(R.string.ai_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }
    return AI_CHANNEL_ID
}

private const val AI_CHANNEL_ID = "ai"
