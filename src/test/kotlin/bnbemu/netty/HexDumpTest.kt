package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HexDumpTest {

    @Test
    fun testEmptyBuffer() {
        val buf = Unpooled.buffer()
        assertEquals("", buf.hexDumpTruncated())
    }

    @Test
    fun testShortBuffer() {
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0x01, 0x02, 0x0A, 0xFF.toByte()))
        val expected = "01 02 0A FF"
        assertEquals(expected, buf.hexDumpTruncated())
        // Ensure reader index was not moved
        assertEquals(0, buf.readerIndex())
        assertEquals(4, buf.readableBytes())
    }

    @Test
    fun testExactLimitBuffer() {
        val bytes = ByteArray(MAX_DUMP_LEN) { it.toByte() }
        val buf = Unpooled.wrappedBuffer(bytes)
        val result = buf.hexDumpTruncated()
        // Should not have ellipsis
        assertTrue(!result.endsWith("..."), "Result should not end with ellipsis when bytes == MAX_DUMP_LEN")
        // Length in bytes: 99 * 3 - 1 = 296
        val hexParts = result.split(" ")
        assertEquals(MAX_DUMP_LEN, hexParts.size)
        assertEquals("00", hexParts[0])
        assertEquals("62", hexParts[98])
    }

    @Test
    fun testLongBufferTruncated() {
        val bytes = ByteArray(150) { (it and 0xFF).toByte() }
        val buf = Unpooled.wrappedBuffer(bytes)
        val result = buf.hexDumpTruncated()

        assertTrue(result.endsWith(" ..."), "Result should end with ' ...'")
        val mainPart = result.removeSuffix(" ...")
        val hexParts = mainPart.split(" ")
        assertEquals(MAX_DUMP_LEN, hexParts.size)
        assertEquals("00", hexParts[0])
        assertEquals("62", hexParts[98])

        // Verify readerIndex is intact
        assertEquals(0, buf.readerIndex())
        assertEquals(150, buf.readableBytes())
    }

    @Test
    fun testCustomMaxLen() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val buf = Unpooled.wrappedBuffer(bytes)
        val result = buf.hexDumpTruncated(maxLen = 3)
        assertEquals("10 20 30 ...", result)
    }
}
