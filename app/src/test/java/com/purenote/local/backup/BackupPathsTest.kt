package com.purenote.local.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zip Slip 防护必须钉死：一旦放行带路径的条目，导入就能写到应用目录之外。
 */
class BackupPathsTest {

    @Test
    fun plainNames_areAccepted() {
        assertEquals("img_20260101.jpg", BackupPaths.sanitizeEntryName("img_20260101.jpg"))
        assertEquals("aud_1.m4a", BackupPaths.sanitizeEntryName("  aud_1.m4a  "))
        assertEquals("中文附件.jpg", BackupPaths.sanitizeEntryName("中文附件.jpg"))
    }

    @Test
    fun pathTraversal_isRejected() {
        assertNull(BackupPaths.sanitizeEntryName("../../databases/purenote.db"))
        assertNull(BackupPaths.sanitizeEntryName(".."))
        assertNull(BackupPaths.sanitizeEntryName("../evil.jpg"))
    }

    @Test
    fun anyPathSeparator_isRejected() {
        assertNull(BackupPaths.sanitizeEntryName("sub/dir/file.jpg"))
        assertNull(BackupPaths.sanitizeEntryName("sub\\dir\\file.jpg"))
        assertNull(BackupPaths.sanitizeEntryName("C:/windows/system32/x.dll"))
    }

    @Test
    fun hiddenAndEmptyNames_areRejected() {
        assertNull(BackupPaths.sanitizeEntryName(""))
        assertNull(BackupPaths.sanitizeEntryName("   "))
        assertNull(BackupPaths.sanitizeEntryName(".hidden"))
    }

    @Test
    fun overlongName_isRejected() {
        assertNull(BackupPaths.sanitizeEntryName("a".repeat(129)))
        assertEquals(128, BackupPaths.sanitizeEntryName("a".repeat(128))!!.length)
    }

    @Test
    fun nullByte_isRejected() {
        assertNull(BackupPaths.sanitizeEntryName("evil\u0000.jpg"))
    }
}
