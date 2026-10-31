package dev.wplayer.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import dev.wplayer.app.R

internal enum class WorkChannel(val id: String, @StringRes val title: Int) {
    DOWNLOADS("downloads", R.string.download_channel),
    AI("ai", R.string.ai_channel),
}

/** A null [fraction] shows an indeterminate bar. */
internal fun progressNotification(
    context: Context,
    channel: WorkChannel,
    @DrawableRes icon: Int,
    title: String,
    text: String,
    fraction: Float?,
): Notification =
    NotificationCompat.Builder(context, ensureChannel(context, channel))
        .setSmallIcon(icon)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

private fun ensureChannel(context: Context, channel: WorkChannel): String {
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (notifications.getNotificationChannel(channel.id) == null) {
        notifications.createNotificationChannel(
            NotificationChannel(channel.id, context.getString(channel.title), NotificationManager.IMPORTANCE_LOW),
        )
    }
    return channel.id
}

private const val PROGRESS_MAX = 1000
