package dev.wplayer.app.data.transcription

import android.app.Application
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.wplayer.app.data.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ModelDownloadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    @Test
    fun aDownloadThatCannotStartSaysWhy() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val store = WhisperModelStore(context, WorkManager.getInstance(context), Dispatchers.IO)
        // What Android 12 and later throw when a foreground service may not start from the background.
        val refused = ForegroundUpdater { _, _, _ ->
            CallbackToFutureAdapter.getFuture { it.setException(IllegalStateException("foreground service not allowed")) }
        }
        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to WhisperModel.FAST.name))
            .setForegroundUpdater(refused)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        ModelDownloadWorker(appContext, workerParameters, store, Dispatchers.IO)
                },
            )
            .build()

        assertEquals(Result.failure(workDataOf(ModelDownloadWorker.KEY_ERROR to "foreground service not allowed")), worker.doWork())
    }
}
