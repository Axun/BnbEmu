package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import bnbemu.handler.room.SelectMapReq
import bnbemu.packet.room.SelectMap
import bnbemu.server.Room
import java.io.File

class SelectMapTest {

    @Test
    fun testSelectMapOpcodeBindings() {
        val recv85 = OpcodeManager.recvOps[85]
        assertEquals("SelectMapReq", recv85, "C->S op 85 must be SelectMapReq")
        assertNotNull(handlerArray[85], "Handler for op 85 must be registered")

        val selectMapSendOp = OpcodeManager.getSendOp(SelectMap(mapId = 175)::class.java)
        assertEquals(108, selectMapSendOp, "SelectMap send opcode must be 108")
    }

    @Test
    fun testSelectMapPacketEncoding() {
        val pkt = SelectMap(result = 0, themeId = 28, mapId = 175)
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(buf.array(), 0, buf.writerIndex())
        val result = inBuf.Decode2
        val themeId = inBuf.Decode2
        val mapId = inBuf.Decode4

        assertEquals(0, result, "result should be 0")
        assertEquals(28, themeId, "themeId should be 28")
        assertEquals(175, mapId, "mapId should be 175")
    }

    @Test
    fun testSelectMapMatchesCaptureBlob() {
        // cap/practice_s2c_op108_10B_0.bin 是 10B 原始帧 (2B opcode 108 + 8B body)
        val binFile = File("cap/practice_s2c_op108_10B_0.bin")
        if (binFile.exists()) {
            val fileBytes = binFile.readBytes()
            val expectedBody = fileBytes.copyOfRange(2, fileBytes.size)

            val pkt = SelectMap(result = 0, themeId = 0, mapId = 172)
            val buf = Unpooled.buffer()
            val sendPkt = SendPacketBase(buf, 3)
            pkt.encode(sendPkt)

            val actualBody = ByteArray(buf.readableBytes())
            buf.readBytes(actualBody)

            assertArrayEquals(expectedBody, actualBody, "SelectMap(0, 0, 172) must match capture blob byte-for-byte")
        }
    }

    @Test
    fun testSelectMapReqHandling() {
        // 用户日志中的真实 payload: A5 75 96 CA 53 3A -> themeId 28, mapId 175
        val raw = byteArrayOf(
            0xA5.toByte(), 0x75.toByte(),
            0x96.toByte(), 0xCA.toByte(), 0x53.toByte(), 0x3A.toByte()
        )
        val inBuf = Unpooled.wrappedBuffer(raw)
        val client = ClientSocket()

        with(SelectMapReq) {
            inBuf.handle(client)
        }

        assertEquals(28, Room.themeId, "Room.themeId should be updated to 28")
        assertEquals(175, Room.mapId, "Room.mapId should be updated to 175")
    }
}
