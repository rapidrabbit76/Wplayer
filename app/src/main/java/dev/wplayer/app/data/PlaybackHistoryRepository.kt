package dev.wplayer.app.data

import dev.wplayer.app.data.local.PlaybackPositionDao
import dev.wplayer.app.data.local.PlaybackPositionEntity
import dev.wplayer.app.data.local.ReadingPositionDao
import dev.wplayer.app.data.local.ReadingPositionEntity
import dev.wplayer.app.data.local.RecentMediaDao
import dev.wplayer.app.data.local.RecentMediaEntity
import dev.wplayer.app.data.local.SubtitleChoiceDao
import dev.wplayer.app.data.local.SubtitleChoiceEntity
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.RecentMedia
import dev.wplayer.app.data.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Resume positions, the pages comic archives were left on, the recently played list and subtitle files picked for a video. */
@Singleton
class PlaybackHistoryRepository @Inject constructor(
    private val positions: PlaybackPositionDao,
    private val readingPositions: ReadingPositionDao,
    private val recents: RecentMediaDao,
    private val subtitleChoices: SubtitleChoiceDao,
    private val lockedFolders: LockedFolders,
) {
    /** Position to resume from, or null when the file is new or was finished. */
    suspend fun resumePosition(mediaKey: String): Double? =
        positions.get(mediaKey)?.positionSeconds?.takeIf { it >= WatchProgress.MIN_RESUME_SECONDS }

    suspend fun save(mediaKey: String, positionSeconds: Double, durationSeconds: Double) {
        val finished = durationSeconds > 0 && durationSeconds - positionSeconds < FINISHED_MARGIN_SECONDS
        if (finished) {
            positions.delete(mediaKey)
        } else {
            positions.upsert(PlaybackPositionEntity(mediaKey, positionSeconds, durationSeconds, System.currentTimeMillis()))
        }
    }

    /** The path of the page the archive [mediaKey] was left on, or null when it is new. */
    suspend fun readingPage(mediaKey: String): String? = readingPositions.get(mediaKey)?.pagePath

    suspend fun saveReadingPage(mediaKey: String, pagePath: String) {
        readingPositions.upsert(ReadingPositionEntity(mediaKey, pagePath, System.currentTimeMillis()))
        readingPositions.trim(READING_POSITION_LIMIT)
    }

    /** Watch progress of unfinished files, by media key. */
    val progress: Flow<Map<String, WatchProgress>> = positions.observeAll().map { rows ->
        rows.associate { it.mediaKey to WatchProgress(it.positionSeconds, it.durationSeconds) }
    }

    /** Newest first. Files in locked folders never appear, even while unlocked. */
    fun recent(limit: Int = RECENT_LIMIT): Flow<List<RecentMedia>> =
        combine(recents.observeLatest(limit), lockedFolders.state) { rows, locks ->
            rows.filterNot { locks.isLocked(it.sourceKey, it.path) }
                .map { RecentMedia(MediaRef(it.sourceKey, it.path, it.name, isDirectory = false), it.playedAtMillis) }
        }

    /** Adds [ref] to the recent list, unless it is in a locked folder. */
    suspend fun recordPlayed(ref: MediaRef) {
        if (lockedFolders.isLocked(ref.sourceKey, ref.path)) return
        recents.upsert(RecentMediaEntity(ref.sourceKey, ref.path, ref.name, System.currentTimeMillis()))
        recents.trim(RECENT_LIMIT)
    }

    /** Forgets where [ref] was left and removes it from the recent list. */
    suspend fun forget(ref: MediaRef) {
        positions.delete(ref.mediaKey)
        readingPositions.delete(ref.mediaKey)
        recents.delete(ref.sourceKey, ref.path)
    }

    /** The subtitle path picked by hand for [mediaKey], in the same source. */
    suspend fun chosenSubtitle(mediaKey: String): String? = subtitleChoices.get(mediaKey)?.subtitlePath

    suspend fun rememberSubtitle(mediaKey: String, subtitlePath: String) {
        subtitleChoices.upsert(SubtitleChoiceEntity(mediaKey, subtitlePath, System.currentTimeMillis()))
        subtitleChoices.trim(SUBTITLE_CHOICE_LIMIT)
    }

    suspend fun clearAll() {
        positions.clear()
        readingPositions.clear()
        recents.clear()
    }

    private companion object {
        const val FINISHED_MARGIN_SECONDS = 15.0
        const val RECENT_LIMIT = 50
        const val SUBTITLE_CHOICE_LIMIT = 500
        const val READING_POSITION_LIMIT = 500
    }
}
