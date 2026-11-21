package bnbemu.netty

import bnbemu.codec.CRC8
import bnbemu.packet.ChannelsGameState
import bnbemu.packet.ConnEstablished
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.EncoderException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodecTest {

    @Test
    fun `Encoder sends plaintext 20-byte EN banner first then encrypted CRC8 frames`() {
        OpcodeManager // ensure YAML opcodes are loaded
        val ch = EmbeddedChannel(Encoder())

        // 1st packet (seq=40): plaintext handshake banner = [0x00][len:2 BE=20][20B body]
        assertTrue(ch.writeOutbound(ConnEstablished()))
        val bannerBuf = ch.readOutbound<ByteBuf>()
        try {
            val banner = ByteArray(bannerBuf.readableBytes()).also { bannerBuf.readBytes(it) }
            val expectedFrame = "00 00 14 00 00 12 00 12 00 00 00 00 01 00 00 00 00 00 00 37 00 00 00".toBA()
            assertArrayEquals(expectedFrame, banner)
        } finally {
            bannerBuf.release()
        }

        // 2nd packet (seq=43): encrypted frame with 6B header + CRC8
        val pkt = ChannelsGameState()
        assertTrue(ch.writeOutbound(pkt))
        val frameBuf = ch.readOutbound<ByteBuf>()
        try {
            val seq = 43
            val expectedHc = (231 xor (102 + seq)).toByte()
            assertEquals(expectedHc, frameBuf.readByte())
            assertEquals(0.toByte(), frameBuf.readByte())
            val payloadLen = frameBuf.readInt() xor 0x96CA5395.toInt()
            // 2B opcode (14) + 8B ChannelsGameState body = 10B
            assertEquals(10, payloadLen)

            val payload = frameBuf.readRetainedSlice(payloadLen)
            try {
                payload.simpleStreamDecrypt3(seq)
                val wireCrc = frameBuf.readByte()
                val expectedCrc = CRC8.UpdateCRC(dwCrcKey = seq, payload).toByte()
                assertEquals(expectedCrc, wireCrc)
                assertEquals(0, frameBuf.readableBytes())

                val opcode = payload.Decode2
                assertEquals(OpcodeManager.getSendOp(pkt.javaClass), opcode)
            } finally {
                payload.release()
            }
        } finally {
            frameBuf.release()
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `Decoder verifies headerCode and CRC8 and decrypts C2S frame`() {
        val ch = EmbeddedChannel(Decoder())
        val seq = 40
        val plain = Unpooled.buffer()
        SendPacketBase(plain).apply {
            Encode2(15)
            Encode1(1)
        }
        val payloadBytes = plain.readableBytes()
        val crc = CRC8.UpdateCRC(dwCrcKey = seq, plain)
        plain.simpleStreamEncrypt3(seq)

        val frame = Unpooled.buffer()
        frame.writeByte(231 xor (192 + seq))
        frame.writeShortLE(13 xor 0xA569) // uid
        frame.writeByte(payloadBytes xor 0x69)
        frame.writeBytes(plain)
        frame.writeByte(crc)
        plain.release()

        assertTrue(ch.writeInbound(frame))
        val decoded = ch.readInbound<ByteBuf>()
        assertNotNull(decoded)
        try {
            assertEquals(15, decoded.Decode2)
            assertEquals(1, decoded.Decode1)
            assertEquals(0, decoded.readableBytes())
        } finally {
            decoded.release()
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `Decoder closes channel on invalid CRC8`() {
        val ch = EmbeddedChannel(Decoder())
        val seq = 40
        val plain = Unpooled.buffer()
        SendPacketBase(plain).apply { Encode2(15) }
        val payloadBytes = plain.readableBytes()
        val badCrc = CRC8.UpdateCRC(dwCrcKey = seq, plain) xor 0xFF
        plain.simpleStreamEncrypt3(seq)

        val frame = Unpooled.buffer()
        frame.writeByte(231 xor (192 + seq))
        frame.writeShortLE(0)
        frame.writeByte(payloadBytes xor 0x69)
        frame.writeBytes(plain)
        frame.writeByte(badCrc)
        plain.release()

        ch.writeInbound(frame)
        assertFalse(ch.isOpen)
        ch.finishAndReleaseAll()
    }

    @Test
    fun `Encoder rejects unregistered PKT instead of emitting -1 opcode`() {
        OpcodeManager.init()
        val ch = EmbeddedChannel(Encoder())
        try {
            val unregistered = PKT { out -> out.Encode1(1) }
            val ex = assertThrows(EncoderException::class.java) {
                ch.writeOutbound(unregistered)
            }
            assertTrue(ex.cause is IllegalArgumentException)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `ClientSocket ignores out-of-range opcode safely`() {
        OpcodeManager.init()
        val ch = EmbeddedChannel(ClientSocket())
        try {
            val buf = Unpooled.buffer()
            SendPacketBase(buf).apply { Encode2(65535) }
            assertFalse(ch.writeInbound(buf))
            assertTrue(ch.isOpen)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `OpcodeManager getRecvOpName handles valid and out-of-range opcodes`() {
        OpcodeManager.init()
        assertEquals("ChannelsStateReq", OpcodeManager.getRecvOpName(15))
        assertNull(OpcodeManager.getRecvOpName(-1))
        assertNull(OpcodeManager.getRecvOpName(handlerArray.size))
        assertNull(OpcodeManager.getRecvOpName(65535))
    }
}
