package dev.wplayer.app.data.transcription

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.wplayer.app.data.model.mediaKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One file's AI subtitles failing or being cancelled leaves the files queued after it alone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TranscriptionQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** What each file's transcription ends with, by path; it runs until completed. */
    private val outcomes = mapOf(FIRST to CompletableDeferred<ListenableWorker.Result>(), SECOND to CompletableDeferred())
    private lateinit var repository: TranscriptionRepository

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ControlledTranscription(appContext, workerParameters, outcomes)
        }
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        repository = TranscriptionRepository(WorkManager.getInstance(context), GeneratedSubtitles(context))
        repository.start(SOURCE, FIRST, "first", startSeconds = 0.0, durationSeconds = 60.0, audioTrack = null)
        repository.start(SOURCE, SECOND, "second", startSeconds = 0.0, durationSeconds = 60.0, audioTrack = null)
    }

    /** A worker still waiting when its database closes fails later, inside whichever test runs next. */
    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        outcomes.values.forEach { it.complete(ListenableWorker.Result.success()) }
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun failingOneFileKeepsTheNextQueued() = runBlocking {
        outcomes.getValue(FIRST).complete(ListenableWorker.Result.failure(workDataOf(TranscriptionWorker.KEY_ERROR to "decoder gone")))

        assertEquals(TranscriptionState.Failed("decoder gone"), withTimeout(TIMEOUT_MS) { state(FIRST).first { it is TranscriptionState.Failed } })
        assertEquals(TranscriptionState.Queued, state(SECOND).first())
    }

    @Test
    fun cancellingOneFileKeepsTheNextQueued() = runBlocking {
        repository.cancel(mediaKey(SOURCE, FIRST))

        assertEquals(TranscriptionState.Idle, withTimeout(TIMEOUT_MS) { state(FIRST).first { it == TranscriptionState.Idle } })
        assertEquals(TranscriptionState.Queued, state(SECOND).first())
    }

    private fun state(path: String) = repository.state(mediaKey(SOURCE, path))

    /** Stands in for the native transcription, which cannot run off the device. */
    private class ControlledTranscription(
        context: Context,
        params: WorkerParameters,
        private val outcomes: Map<String, CompletableDeferred<Result>>,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = outcomes.getValue(inputData.getString(TranscriptionWorker.KEY_PATH)!!).await()
    }

    private companion object {
        const val SOURCE = "smb:1"
        const val FIRST = "/movies/first.mkv"
        const val SECOND = "/movies/second.mkv"
        const val TIMEOUT_MS = 5_000L
    }
}
