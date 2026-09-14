package com.purenote.local.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规范 §11.1 / §16：坏备份必须被拒绝。
 * 纯 JVM 测试——不需要设备就能把"截断/改字节/缺附件/多余条目"全部构造出来。
 */
class BackupVerifierTest {

    private fun digest(path: String, content: String) =
        EntryDigest(path, content.toByteArray().size.toLong(), BackupVerifier.sha256Of(content.toByteArray()))

    private fun manifestOf(vararg entries: ManifestEntry) = BackupManifest(
        appVersion = "test", sourceSchema = 9, libraryId = "lib", backupId = "b1",
        createdAt = 1L, entries = entries.toList(),
    )

    private fun entry(path: String, content: String) =
        ManifestEntry(path, content.toByteArray().size.toLong(), BackupVerifier.sha256Of(content.toByteArray()))

    @Test
    fun matchingManifestPasses() {
        val actual = listOf(digest("backup.json", "{}"), digest("attachments/a.jpg", "AAA"))
        val manifest = manifestOf(entry("backup.json", "{}"), entry("attachments/a.jpg", "AAA"))
        assertEquals(BackupVerifier.Outcome.Ok, BackupVerifier.verify(manifest, actual))
    }

    @Test
    fun truncatedAttachmentIsRejected() {
        // 同步工具截断 / 传输中断：内容短了一截
        val manifest = manifestOf(entry("attachments/a.jpg", "AAAAAAAA"))
        val actual = listOf(digest("attachments/a.jpg", "AAA"))
        val outcome = BackupVerifier.verify(manifest, actual) as BackupVerifier.Outcome.Failed
        assertTrue(outcome.reasons.any { it.contains("大小不符") })
    }

    @Test
    fun tamperedBytesAreRejected() {
        // 长度一样、内容被改过：只看大小是抓不住的，必须靠 SHA-256
        val manifest = manifestOf(entry("attachments/a.jpg", "AAAA"))
        val actual = listOf(digest("attachments/a.jpg", "AAAB"))
        val outcome = BackupVerifier.verify(manifest, actual) as BackupVerifier.Outcome.Failed
        assertTrue("必须因校验值不符被拒", outcome.reasons.any { it.contains("校验值不符") })
        assertEquals("长度相同不应报大小", 0, outcome.reasons.count { it.contains("大小不符") })
    }

    @Test
    fun declaredButMissingAttachmentIsRejected() {
        val manifest = manifestOf(entry("backup.json", "{}"), entry("attachments/gone.jpg", "X"))
        val actual = listOf(digest("backup.json", "{}"))
        val outcome = BackupVerifier.verify(manifest, actual) as BackupVerifier.Outcome.Failed
        assertTrue(outcome.reasons.any { it.contains("缺失") })
    }

    @Test
    fun undeclaredEntryIsRejected() {
        // 清单与内容不是同一次生成的 -> 不能当成有效备份
        val manifest = manifestOf(entry("backup.json", "{}"))
        val actual = listOf(digest("backup.json", "{}"), digest("attachments/sneaky.jpg", "Z"))
        val outcome = BackupVerifier.verify(manifest, actual) as BackupVerifier.Outcome.Failed
        assertTrue(outcome.reasons.any { it.contains("未声明") })
    }

    @Test
    fun duplicateEntriesAreRejected() {
        val manifest = manifestOf(entry("backup.json", "{}"))
        val actual = listOf(digest("backup.json", "{}"), digest("backup.json", "{}"))
        val outcome = BackupVerifier.verify(manifest, actual) as BackupVerifier.Outcome.Failed
        assertTrue(outcome.reasons.any { it.contains("重复条目") })
    }

    @Test
    fun allProblemsAreReportedTogether() {
        // 用户需要一次看到全部原因，而不是修一个再发现一个
        val manifest = manifestOf(entry("backup.json", "{}"), entry("attachments/a.jpg", "AAAA"))
        val actual = listOf(digest("backup.json", "{}"), digest("attachments/a.jpg", "BB"))
        val outcome = BackupVerifier.verify(manifest, actual) as BackupVerifier.Outcome.Failed
        assertTrue(outcome.reasons.isNotEmpty())
    }
}
