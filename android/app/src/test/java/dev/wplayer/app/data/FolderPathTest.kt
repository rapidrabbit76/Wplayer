package dev.wplayer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderPathTest {
    @Test
    fun segmentsLeadFromTheRootToTheFolder() {
        assertEquals(emptyList<PathSegment>(), pathSegments("/"))
        assertEquals(
            listOf(PathSegment("Download", "/Download"), PathSegment("영상", "/Download/영상"), PathSegment("1-4화", "/Download/영상/1-4화")),
            pathSegments("/Download/영상/1-4화"),
        )
    }

    @Test
    fun parentOfTheRootIsNone() {
        assertNull(parentPath("/"))
        assertEquals("/", parentPath("/Download"))
        assertEquals("/Download", parentPath("/Download/영상"))
    }

    @Test
    fun folderOfTheRootIsTheRoot() {
        assertEquals("/", folderOf("/"))
        assertEquals("/", folderOf("/a.mkv"))
        assertEquals("/Download", folderOf("/Download/a.mkv"))
    }
}
