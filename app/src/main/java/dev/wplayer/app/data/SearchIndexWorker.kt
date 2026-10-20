package dev.wplayer.app.data

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.di.ApplicationScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Indexes the file names of every network source so subfolder searches answer
 * at once. Folders indexed within the last half day are skipped, so a run after
 * normal browsing only lists what changed or was never seen.
 */
@HiltWorker
class SearchIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: SourceRepository,
    private val browser: BrowserRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val includeHidden = settings.settings.first().browser.showHiddenFiles
        for (source in sources.sources.first().filter { it.protocol != SourceProtocol.LOCAL }) {
            try {
                val refresh = browser.refreshIndex(
                    sourceKey = source.key,
                    root = "/",
                    maxDepth = MAX_DEPTH,
                    maxAgeMillis = TimeUnit.HOURS.toMillis(12),
                    maxFolders = MAX_FOLDERS,
                    includeHidden = includeHidden,
                )
                Log.i(TAG, "indexed ${source.key}: ${refresh.foldersVisited} folders")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreachable server (switched off, out of reach) only skips that source.
                Log.i(TAG, "cannot index ${source.key}: ${e.message}")
            }
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "SearchIndexWorker"
        const val MAX_DEPTH = 32
        const val MAX_FOLDERS = 50_000
    }
}

/** Keeps [SearchIndexWorker] scheduled daily while the setting is on. */
@Singleton
class SearchIndexScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            settings.settings.map { it.browser.backgroundIndexing }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
                } else {
                    workManager.cancelUniqueWork(WORK_NAME)
                }
            }
        }
    }

    private fun request() = PeriodicWorkRequestBuilder<SearchIndexWorker>(1, TimeUnit.DAYS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build(),
        )
        .build()

    private companion object {
        const val WORK_NAME = "search-index"
    }
}
