package dev.wplayer.app.data

import dev.wplayer.app.data.local.LockedFolderDao
import dev.wplayer.app.data.local.LockedFolderEntity
import dev.wplayer.app.data.local.escapeLike
import dev.wplayer.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Locked folders and whether this session has unlocked them. */
data class LockState(val folders: Set<FolderKey> = emptySet(), val unlocked: Boolean = false) {
    /** Whether [path] is a locked folder or inside one. */
    fun isLocked(sourceKey: String, path: String): Boolean =
        folders.any { it.sourceKey == sourceKey && (path == it.path || path.startsWith(it.path.trimEnd('/') + "/")) }

    /** Whether [path] is a locked folder or holds one somewhere below it. */
    fun holdsLock(sourceKey: String, path: String): Boolean =
        folders.any { it.sourceKey == sourceKey && (it.path == path || it.path.startsWith(path.trimEnd('/') + "/")) }

    /** Locked and not unlocked this session: its contents stay out of sight. */
    fun isHidden(sourceKey: String, path: String): Boolean = !unlocked && isLocked(sourceKey, path)
}

data class FolderKey(val sourceKey: String, val path: String)

/**
 * Folders the user locked. Opening one asks for the device's own fingerprint,
 * face or screen lock; that unlocks all of them until the app leaves the
 * foreground. Files inside are kept out of the recent list and the thumbnail
 * cache even while unlocked.
 */
@Singleton
class LockedFolders @Inject constructor(
    private val dao: LockedFolderDao,
    @ApplicationScope scope: CoroutineScope,
) {
    private val unlocked = MutableStateFlow(false)

    val state: StateFlow<LockState> = combine(
        dao.observeAll().map { rows -> rows.map { FolderKey(it.sourceKey, it.path) }.toSet() },
        unlocked.asStateFlow(),
    ) { folders, open -> LockState(folders, open) }
        .stateIn(scope, SharingStarted.Eagerly, LockState())

    fun isLocked(sourceKey: String, path: String): Boolean = state.value.isLocked(sourceKey, path)

    suspend fun lock(sourceKey: String, path: String) = dao.insert(LockedFolderEntity(sourceKey, path))

    suspend fun removeLock(sourceKey: String, path: String) = dao.delete(sourceKey, path)

    /** After [path] was deleted: its lock and those of folders below it go too. */
    suspend fun forgetFolder(sourceKey: String, path: String) =
        dao.deleteTree(sourceKey, path, escapeLike(path.trimEnd('/')) + "/%")

    suspend fun forgetSource(sourceKey: String) = dao.deleteSource(sourceKey)

    /** After the device authenticated the user. */
    fun unlockSession() {
        unlocked.value = true
    }

    /** When the app leaves the foreground. */
    fun lockSession() {
        unlocked.value = false
    }
}
