package dev.wplayer.app.data

import dev.wplayer.app.data.local.PlaylistDao
import dev.wplayer.app.data.local.PlaylistEntity
import dev.wplayer.app.data.local.PlaylistItemEntity
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.Playlist
import dev.wplayer.app.data.model.PlaylistItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User playlists. Folders can be added too, which makes a playlist work as a bookmark list. */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
) {
    val playlists: Flow<List<Playlist>> = dao.observeSummaries().map { rows ->
        rows.map { Playlist(it.id, it.name, it.itemCount) }
    }

    /** The playlist's name, or null once it was deleted. */
    fun name(playlistId: Long): Flow<String?> = dao.observe(playlistId).map { it?.name }

    fun items(playlistId: Long): Flow<List<PlaylistItem>> = dao.observeItems(playlistId).map { rows -> rows.map { it.toModel() } }

    /** Files only, in playlist order: what playing the playlist goes through. */
    suspend fun playableItems(playlistId: Long): List<MediaRef> =
        dao.items(playlistId).filterNot { it.isDirectory }.map { it.toModel().ref }

    suspend fun create(name: String): Long = dao.insert(PlaylistEntity(name = name.trim(), createdAtMillis = System.currentTimeMillis()))

    suspend fun rename(playlistId: Long, name: String) = dao.rename(playlistId, name.trim())

    suspend fun delete(playlistId: Long) = dao.delete(playlistId)

    /** Adds [refs] that are not in the playlist yet; returns how many were added. */
    suspend fun add(playlistId: Long, refs: List<MediaRef>): Int = dao.append(
        playlistId,
        refs.map { PlaylistItemEntity(playlistId = playlistId, sourceKey = it.sourceKey, path = it.path, name = it.name, isDirectory = it.isDirectory, sortOrder = 0) },
    )

    suspend fun remove(itemId: Long) = dao.deleteItem(itemId)

    suspend fun reorder(itemIds: List<Long>) = dao.reorder(itemIds)

    private fun PlaylistItemEntity.toModel() = PlaylistItem(id, MediaRef(sourceKey, path, name, isDirectory))
}
