package dev.wplayer.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.wplayer.app.data.local.AppDatabase
import dev.wplayer.app.data.model.MediaRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A server's listing decides download names, never where outside the download folder a file lands. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DownloadDestinationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() {
        database.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun namesThatClimbOutStayInTheDownloadFolder() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val repository = DownloadRepository(database.downloadDao(), WorkManager.getInstance(context), Dispatchers.IO)
        val names = listOf("../../evil.mkv", "..", "season/../../evil.mkv")

        names.forEach { repository.enqueue(MediaRef("ftp:1", "/$it", it, isDirectory = false)) }

        val folder = repository.folder.canonicalFile
        val destinations = repository.downloads.first().map { File(it.destPath).canonicalFile }
        assertEquals(names.size, destinations.size)
        destinations.forEach { assertEquals(folder, it.parentFile) }
    }
}
