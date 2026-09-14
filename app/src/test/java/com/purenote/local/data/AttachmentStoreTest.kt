package com.purenote.local.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 规范 §10 / §16：附件先暂存、校验后发布、原件不可变、失败必须给出原因。
 * 用 JVM 临时目录测试，不需要设备。
 */
class AttachmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AttachmentStore =
        AttachmentStore(
            stagingDir = File(tmp.root, "staging"),
            publishedDir = File(tmp.root, "images"),
        )

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun stagingComputesSizeAndHashWithoutTouchingThePublishedDirectory() {
        val s = store()
        val payload = bytes(4096)
        val outcome = s.stage(ByteArrayInputStream(payload), "jpg")
        val published = (outcome as AttachmentStore.Outcome.Ok).published

        assertEquals(4096L, published.sizeBytes)
        assertTrue("暂存文件应存在", File(tmp.root, "staging").listFiles()!!.isNotEmpty())
        assertFalse("暂存阶段不应出现在正式目录", File(tmp.root, "images").exists())

        // SHA-256 应与独立计算一致
        val staged = File(tmp.root, "staging").listFiles()!!.single()
        assertEquals(s.sha256Of(staged), published.sha256)
        assertEquals(64, published.sha256.length)
    }

    @Test
    fun emptySourceIsRejectedAndLeavesNothingBehind() {
        val s = store()
        val outcome = s.stage(ByteArrayInputStream(ByteArray(0)), "jpg")
        assertTrue("空来源必须失败而不是返回一个文件名", outcome is AttachmentStore.Outcome.Failed)
        val staged = File(tmp.root, "staging")
        assertTrue("不得留下半成品", !staged.exists() || staged.listFiles()!!.isEmpty())
    }

    @Test
    fun publishMovesTheFileAndVerifiesItsSize() {
        val s = store()
        val staged = File(tmp.root, "staging").apply { mkdirs() }
        val tmpFile = File(staged, "abc.jpg.tmp").apply { writeBytes(bytes(2048)) }

        val outcome = s.publish(tmpFile, attachmentId = "abc", extension = "jpg")
        val published = (outcome as AttachmentStore.Outcome.Ok).published

        assertEquals("abc.jpg", published.fileName)
        assertEquals(2048L, published.sizeBytes)
        assertEquals("abc", published.attachmentId)
        assertFalse("暂存文件应已被搬走", tmpFile.exists())
        assertTrue(s.resolve(published.fileName).exists())
    }

    @Test
    fun publishNeverOverwritesAnExistingAttachment() {
        val s = store()
        val staging = File(tmp.root, "staging").apply { mkdirs() }
        val images = File(tmp.root, "images").apply { mkdirs() }

        // 先占住 "fixed.jpg"
        File(images, "fixed.jpg").writeBytes(bytes(10))

        val incoming = File(staging, "incoming.jpg.tmp").apply { writeBytes(bytes(20)) }
        val outcome = s.publish(incoming, attachmentId = "fixed", extension = "jpg")
        val published = (outcome as AttachmentStore.Outcome.Ok).published

        assertNotEquals("必须换一个新 ID，绝不覆盖既有附件", "fixed.jpg", published.fileName)
        assertEquals("既有附件内容不得被改动", 10L, File(images, "fixed.jpg").length())
        assertEquals(20L, s.resolve(published.fileName).length())
    }

    @Test
    fun missingStagingFileIsReportedAsFailureNotSuccess() {
        val s = store()
        val outcome = s.publish(File(tmp.root, "nope.tmp"), "id", "jpg")
        assertTrue(outcome is AttachmentStore.Outcome.Failed)
    }

    @Test
    fun publishDetectsTruncation() {
        // 构造"暂存文件被截断"的场景：publish 后再次核对大小
        val s = store()
        val staging = File(tmp.root, "staging").apply { mkdirs() }
        val tmpFile = File(staging, "x.png.tmp").apply { writeBytes(ByteArray(0)) }
        val outcome = s.publish(tmpFile, "x", "png")
        assertTrue("空文件必须被拒绝", outcome is AttachmentStore.Outcome.Failed)
    }

    @Test
    fun callersCanTellFailureApartFromSuccess() {
        // 原实现把一切折叠成 null，调用方无法区分"没有图"和"写盘失败"
        val s = store()
        val ok = s.stage(ByteArrayInputStream(bytes(16)), "jpg")
        val bad = s.stage(ByteArrayInputStream(ByteArray(0)), "jpg")
        assertTrue(ok is AttachmentStore.Outcome.Ok)
        assertTrue(bad is AttachmentStore.Outcome.Failed)
        assertTrue("失败必须带原因", (bad as AttachmentStore.Outcome.Failed).detail.isNotBlank())
    }

    @Test
    fun extensionIsNormalized() {
        val s = store()
        val staged = s.stage(ByteArrayInputStream(bytes(8)), "..JPG") as AttachmentStore.Outcome.Ok
        // 暂存名带 .tmp 后缀；规范化后的扩展名在发布后可见
        val published = s.publish(
            File(File(tmp.root, "staging"), staged.published.fileName),
            staged.published.attachmentId, "..JPG",
        ) as AttachmentStore.Outcome.Ok
        assertTrue("扩展名应被规范化，实际=" + published.published.fileName, published.published.fileName.endsWith(".jpg"))
    }
}
