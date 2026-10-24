package dev.wplayer.app.ui.common

import dev.wplayer.core.ffi.CoreException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserErrorTest {
    @Test
    fun `an archive the core cannot read gets its own reason, keeping why`() {
        assertEquals(
            UserError(UserError.Reason.UNSUPPORTED_ARCHIVE, "encrypted archive"),
            UserError.fromArchive(CoreException.Unsupported("encrypted archive")),
        )
    }

    @Test
    fun `other archive failures read as they do anywhere`() {
        assertEquals(UserError.Reason.NOT_FOUND, UserError.fromArchive(CoreException.NotFound("/a.cbz")).reason)
        assertEquals(UserError.Reason.UNSUPPORTED, UserError.from(CoreException.Unsupported("hevc")).reason)
    }
}
