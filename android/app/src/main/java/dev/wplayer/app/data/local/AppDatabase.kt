package dev.wplayer.app.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        PlaybackPositionEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        RecentMediaEntity::class,
        DownloadEntity::class,
        IndexedEntryEntity::class,
        IndexedFolderEntity::class,
        LockedFolderEntity::class,
        SubtitleChoiceEntity::class,
        ReadingPositionEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentMediaDao(): RecentMediaDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun lockedFolderDao(): LockedFolderDao
    abstract fun subtitleChoiceDao(): SubtitleChoiceDao
    abstract fun readingPositionDao(): ReadingPositionDao
}
