package dev.wplayer.app.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wplayer.app.data.AppSettingsSerializer
import androidx.work.WorkManager
import dev.wplayer.app.data.local.AppDatabase
import dev.wplayer.app.data.local.DownloadDao
import dev.wplayer.app.data.local.LockedFolderDao
import dev.wplayer.app.data.local.PlaybackPositionDao
import dev.wplayer.app.data.local.PlaylistDao
import dev.wplayer.app.data.local.ReadingPositionDao
import dev.wplayer.app.data.local.RecentMediaDao
import dev.wplayer.app.data.local.SearchIndexDao
import dev.wplayer.app.data.local.SourceDao
import dev.wplayer.app.data.local.SubtitleChoiceDao
import dev.wplayer.app.data.model.AppSettings
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Scope for work that must outlive a screen, e.g. saving the playback position on exit. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

// A corrupt file is replaced by defaults rather than failing every read.
private val Context.settingsDataStore: DataStore<AppSettings> by dataStore(
    fileName = "settings.json",
    serializer = AppSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "wplayer.db").build()

    @Provides
    fun sourceDao(database: AppDatabase): SourceDao = database.sourceDao()

    @Provides
    fun playbackPositionDao(database: AppDatabase): PlaybackPositionDao = database.playbackPositionDao()

    @Provides
    fun playlistDao(database: AppDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun recentMediaDao(database: AppDatabase): RecentMediaDao = database.recentMediaDao()

    @Provides
    fun downloadDao(database: AppDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun searchIndexDao(database: AppDatabase): SearchIndexDao = database.searchIndexDao()

    @Provides
    fun lockedFolderDao(database: AppDatabase): LockedFolderDao = database.lockedFolderDao()

    @Provides
    fun subtitleChoiceDao(database: AppDatabase): SubtitleChoiceDao = database.subtitleChoiceDao()

    @Provides
    fun readingPositionDao(database: AppDatabase): ReadingPositionDao = database.readingPositionDao()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<AppSettings> = context.settingsDataStore

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)

    /** Work launched here is not awaited, so a failure (a full disk while saving) is logged instead of crashing the app. */
    private val logFailures = CoroutineExceptionHandler { _, error -> Log.w(TAG, "background work failed", error) }

    private const val TAG = "ApplicationScope"
}
