package com.rtcomm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上传分片长度与元数据自洽性校验的边界测试。 */
class FileTransferChunkTest {

    @Test
    fun chunkLen_lastChunkIsRemainder() {
        // 250 字节 / 100 = 3 片：100 + 100 + 50
        assertEquals(100, FileTransfer.chunkLen(0, 100, 250, 3))
        assertEquals(100, FileTransfer.chunkLen(1, 100, 250, 3))
        assertEquals(50, FileTransfer.chunkLen(2, 100, 250, 3))
    }

    @Test
    fun chunkLen_exactMultiple() {
        // 300 字节 / 100 恰好 3 片，最后一片仍为整片
        assertEquals(100, FileTransfer.chunkLen(2, 100, 300, 3))
    }

    @Test
    fun validChunkMeta_acceptsConsistentMeta() {
        assertTrue(FileTransfer.validChunkMeta(100, 3, 250))
        assertTrue(FileTransfer.validChunkMeta(100, 3, 300))
    }

    @Test
    fun validChunkMeta_rejectsInconsistentCount() {
        // size 300 需要 3 片，服务端声称 2 片 => 不自洽
        assertFalse(FileTransfer.validChunkMeta(100, 2, 300))
    }

    @Test
    fun validChunkMeta_rejectsInvalidRanges() {
        assertFalse(FileTransfer.validChunkMeta(0, 3, 250))
        assertFalse(FileTransfer.validChunkMeta(100, 0, 250))
        assertFalse(FileTransfer.validChunkMeta(100, 3, 0))
        // 超过 16MB 硬上限
        assertFalse(FileTransfer.validChunkMeta(16 * 1024 * 1024 + 1, 1, 16L * 1024 * 1024 + 1))
    }
}
