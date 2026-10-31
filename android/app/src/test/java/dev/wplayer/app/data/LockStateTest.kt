package dev.wplayer.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockStateTest {
    private val locked = LockState(folders = setOf(FolderKey("server", "/Private")))

    @Test
    fun aLockCoversTheFolderAndEverythingBelowIt() {
        assertTrue(locked.isLocked("server", "/Private"))
        assertTrue(locked.isLocked("server", "/Private/Trips/a.mkv"))
    }

    @Test
    fun aLockDoesNotCoverSiblingsSharingItsNameOrOtherSources() {
        assertFalse(locked.isLocked("server", "/PrivateX"))
        assertFalse(locked.isLocked("server", "/Video/Private"))
        assertFalse(locked.isLocked("other", "/Private"))
    }

    @Test
    fun unlockingTheSessionShowsLockedFoldersWithoutRemovingTheLock() {
        assertTrue(locked.isHidden("server", "/Private/a.mkv"))
        val open = locked.copy(unlocked = true)
        assertFalse(open.isHidden("server", "/Private/a.mkv"))
        assertTrue(open.isLocked("server", "/Private/a.mkv"))
    }

    @Test
    fun aFolderHoldsALockWhenItOrAFolderBelowItIsLocked() {
        val nested = LockState(folders = setOf(FolderKey("server", "/Video/Private")))
        assertTrue(nested.holdsLock("server", "/Video"))
        assertTrue(nested.holdsLock("server", "/Video/Private"))
        assertFalse(nested.holdsLock("server", "/Video/Private/Trips"))
        assertFalse(nested.holdsLock("server", "/Vid"))
        assertFalse(nested.holdsLock("other", "/Video"))
    }
}
