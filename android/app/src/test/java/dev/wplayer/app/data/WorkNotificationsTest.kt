package dev.wplayer.app.data

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class WorkNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `shows ongoing progress in a low importance channel`() {
        val notification = progressNotification(
            context, WorkChannel.DOWNLOADS, android.R.drawable.stat_sys_download, title = "a.mkv", text = "Downloading", fraction = 0.25f,
        )

        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("downloads")
        assertEquals(context.getString(R.string.download_channel), channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("downloads", notification.channelId)
        assertEquals("a.mkv", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Downloading", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(1000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(250, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_ONLY_ALERT_ONCE
        assertEquals(flags, notification.flags and flags)
    }

    @Test
    fun `an unknown fraction shows a bar with no end`() {
        val notification = progressNotification(context, WorkChannel.AI, android.R.drawable.ic_menu_edit, title = "AI", text = "model", fraction = null)

        assertEquals("ai", notification.channelId)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }
}
