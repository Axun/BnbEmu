package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import bnbemu.handler.room.StartGameReq
import bnbemu.handler.room.loadMapsYaml
import bnbemu.handler.room.mapsYaml
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.server.Room
import bnbemu.server.bomber
import com.charleskorn.kaml.decodeFromStream
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertNotNull
import java.io.File

class GameStartMapInfoTest {

    @Test
    fun testGameStartMapInfoOpcodeBinding() {
        val sendOp = OpcodeManager.getSendOp(GameStartMapInfo()::class.java)
        assertEquals(112, sendOp, "GameStartMapInfo send opcode must be 112")
    }

    @Test
    fun testCipherStrRoundTripAndMatchesExpected() {
        val str = "Cemetery04"
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        sendPkt.EncodeCipherStr(str)

        // Encode2(10) -> 0x000A ^ 0xA569 = 0xA563
        val lenBytes = ByteArray(2)
        buf.readBytes(lenBytes)
        assertEquals(0xA5.toByte(), lenBytes[0])
        assertEquals(0x63.toByte(), lenBytes[1])

        // 10 字节加密字串: f3 a7 36 d6 ef b8 36 e1 91 59
        val cipherBytes = ByteArray(10)
        buf.readBytes(cipherBytes)
        val expectedHex = "f3a736d6efb836e19159"
        val actualHex = cipherBytes.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, actualHex, "Encoded cipher string bytes must match 0x75daf0 algorithm")

        // 重新包装解码并断言还原明文
        val fullBuf = Unpooled.buffer()
        SendPacketBase(fullBuf, 3).EncodeCipherStr(str)
        val decoded = fullBuf.DecodeCipherStr
        assertEquals(str, decoded, "DecodeCipherStr must recover the original string")
    }

    @Test
    fun testGameStartMapInfoMatchesCaptureBlob() {
        val binFile = File("cap/practice_s2c_op112_167B_0.bin")
        if (binFile.exists()) {
            val fileBytes = binFile.readBytes()
            val expectedBody = fileBytes.copyOfRange(2, fileBytes.size)

            // 重置 slot 0 角色为默认 5
            Room.slots[0].bomber = 5

            val pkt = GameStartMapInfo()
            val buf = Unpooled.buffer()
            val sendPkt = SendPacketBase(buf, 3)
            pkt.encode(sendPkt)

            val actualBody = ByteArray(buf.readableBytes())
            buf.readBytes(actualBody)

            assertEquals(expectedBody.size, actualBody.size, "Payload size must match 165 bytes")
            assertArrayEquals(expectedBody, actualBody, "GameStartMapInfo() must match capture blob byte-for-byte")
        }
    }

    @Test
    fun testGameStartMapInfoFieldsDecode() {
        Room.slots[0].bomber = 5
        val pkt = GameStartMapInfo(
            randomSeed = 0x7E022E6B,
            themeId = 29,
            mapName = "Cemetery04",
            mapId = 176,
            gameOptions = 15,
            flag = 0
        )
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        val seed = buf.Decode4
        val theme = buf.Decode2
        val name = buf.DecodeCipherStr
        val mapId = buf.Decode4
        val opt = buf.Decode1
        val flag = buf.Decode1
        val slotCount = buf.Decode1

        assertEquals(0x7E022E6B, seed)
        assertEquals(29, theme)
        assertEquals("Cemetery04", name)
        assertEquals(176, mapId)
        assertEquals(15, opt)
        assertEquals(0, flag)
        assertEquals(8, slotCount)

        val expectedSpawns = intArrayOf(2, 1, 6, 0, 3, 4, 5, 7)
        for (i in 0 until slotCount) {
            val idx = buf.Decode1
            val spawn = buf.Decode1
            val bomber = buf.Decode1
            val team = buf.Decode1
            val state = buf.Decode1
            val p1 = buf.Decode4
            val p2 = buf.Decode4
            val p3 = buf.Decode4

            assertEquals(i, idx)
            assertEquals(expectedSpawns[i], spawn)
            assertEquals(5, bomber)
            assertEquals(if (i == 0) 0 else 8, team)
            assertEquals(0, state)
            assertEquals(0, p1)
            assertEquals(0, p2)
            assertEquals(0, p3)
        }

        val obsCount = buf.Decode1
        val f1 = buf.Decode1
        val f2 = buf.Decode1
        val timeLimit = buf.Decode1

        assertEquals(0, obsCount)
        assertEquals(0, f1)
        assertEquals(0, f2)
        assertEquals(0, timeLimit)
        assertEquals(0, buf.readableBytes(), "All bytes must be consumed")
    }

    @Test
    fun testGameStartMapInfoDynamicBomber() {
        // 当玩家更换角色为 2 时
        Room.slots[0].bomber = 2

        val pkt = GameStartMapInfo()
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        buf.Decode4 // seed
        buf.Decode2 // theme
        buf.DecodeCipherStr // name
        buf.Decode4 // mapId
        buf.Decode1 // opt
        buf.Decode1 // flag
        buf.Decode1 // slotCount

        val idx0 = buf.Decode1
        val spawn0 = buf.Decode1
        val bomber0 = buf.Decode1
        assertEquals(0, idx0)
        assertEquals(2, spawn0)
        assertEquals(2, bomber0, "Slot 0 bomber must reflect Room.slots[0].bomber")

        // 还原
        Room.slots[0].bomber = 5
    }

    @Test
    fun testLoadMapsYaml() {
        val decoded = loadMapsYaml()
        assertEquals("Cemetery03", decoded[175]?.get("name"))
        assertEquals("Cemetery04", decoded[176]?.get("name"))
        assertEquals("Cemetery03", mapsYaml[175]?.get("name"))
        assertEquals("Cemetery04", mapsYaml[176]?.get("name"))
    }

    @Test
    fun testStartGameReqDynamicMapName() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)

        // 构造 StartGameReq 载荷 (mapId=176, themeId=29, subType=0, roomSelectedMapId=176, isCustomMap=0, fileCheckType=1, eventParam=0)
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        sendPkt.Encode4(176)
        sendPkt.Encode2(29)
        sendPkt.Encode1(0)
        sendPkt.Encode4(176)
        sendPkt.Encode1(0)
        sendPkt.Encode1(1) // fileCheckType = 1 (无需 CRC)
        sendPkt.Encode4(0) // eventParam

        with(StartGameReq) {
            buf.handle(client)
        }

        // 伺服器回覆序列: PracticeAiLevel, GameStartMapInfo, PracticeStartInfo, PracticeBoard
        val pkt1 = ch.readOutbound<PKT>() // PracticeAiLevel
        val pkt2 = ch.readOutbound<PKT>() // GameStartMapInfo
        val pkt3 = ch.readOutbound<PKT>() // PracticeStartInfo
        val pkt4 = ch.readOutbound<PKT>() // PracticeBoard

        assertNotNull(pkt2)
        val encBuf = Unpooled.buffer()
        val sPkt = SendPacketBase(encBuf, 3)
        pkt2.encode(sPkt)

        encBuf.Decode4 // seed
        encBuf.Decode2 // theme
        val mapName = encBuf.DecodeCipherStr
        val mapId = encBuf.Decode4
        assertEquals(176, mapId)
        assertEquals("Cemetery04", mapName)
    }

    @Test
    fun testStartGameReqDynamicMapNameFallback() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)

        // 构造 StartGameReq 载荷，未知 mapId=999
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        sendPkt.Encode4(999)
        sendPkt.Encode2(10)
        sendPkt.Encode1(0)
        sendPkt.Encode4(999)
        sendPkt.Encode1(0)
        sendPkt.Encode1(1)
        sendPkt.Encode4(0)

        with(StartGameReq) {
            buf.handle(client)
        }

        ch.readOutbound<PKT>() // PracticeAiLevel
        val pkt2 = ch.readOutbound<PKT>() // GameStartMapInfo
        assertNotNull(pkt2)

        val encBuf = Unpooled.buffer()
        val sPkt = SendPacketBase(encBuf, 3)
        pkt2.encode(sPkt)

        encBuf.Decode4 // seed
        encBuf.Decode2 // theme
        val mapName = encBuf.DecodeCipherStr
        val mapId = encBuf.Decode4
        assertEquals(999, mapId)
        assertEquals("Cemetery03", mapName, "Fallback mapName should be Cemetery03")
    }
}
