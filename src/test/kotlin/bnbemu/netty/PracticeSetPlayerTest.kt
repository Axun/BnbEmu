package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import bnbemu.handler.CreatePracticeSessionReq
import bnbemu.packet.PracticeSetPlayer
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.server.Room
import bnbemu.server.User
import bnbemu.server.bomber

class PracticeSetPlayerTest {

    @Test
    fun testPracticeSetPlayerDynamicBomber() {
        val savedBomber = Room.slots[0].bomber
        try {
            // 1. 验证皮蛋 (bomber = 0)
            Room.slots[0].bomber = 0
            val pkt0 = PracticeSetPlayer(slot = 0)
            val buf0 = Unpooled.buffer()
            pkt0.encode(SendPacketBase(buf0, 3))
            assertEquals(0, buf0.Decode1, "Slot index must be 0")
            assertEquals(0, buf0.Decode1, "PracticeSetPlayer must return 0 (Dao)")

            // 2. 验证宝宝 (bomber = 5)
            Room.slots[0].bomber = 5
            val pkt5 = PracticeSetPlayer(slot = 0)
            val buf5 = Unpooled.buffer()
            pkt5.encode(SendPacketBase(buf5, 3))
            assertEquals(0, buf5.Decode1)
            assertEquals(5, buf5.Decode1, "PracticeSetPlayer must return 5 (Bazzi)")

            // 3. 验证蓝妹妹 (bomber = 10)
            Room.slots[0].bomber = 10
            val pkt10 = PracticeSetPlayer(slot = 0)
            val buf10 = Unpooled.buffer()
            pkt10.encode(SendPacketBase(buf10, 3))
            assertEquals(0, buf10.Decode1)
            assertEquals(10, buf10.Decode1, "PracticeSetPlayer must return 10 (LuxuryMarid)")

            // 4. 显式传参 override
            val pktOverride = PracticeSetPlayer(slot = 0, bomber = 6)
            val bufOverride = Unpooled.buffer()
            pktOverride.encode(SendPacketBase(bufOverride, 3))
            assertEquals(0, bufOverride.Decode1)
            assertEquals(6, bufOverride.Decode1, "Explicit bomber parameter must be respected")

            // 5. AI 槽位 (slot 1..7) 默认为 5
            Room.slots[1].bomber = 5
            val pktAi = PracticeSetPlayer(slot = 1)
            val bufAi = Unpooled.buffer()
            pktAi.encode(SendPacketBase(bufAi, 3))
            assertEquals(1, bufAi.Decode1)
            assertEquals(5, bufAi.Decode1, "AI slot must default to 5")
        } finally {
            Room.slots[0].bomber = savedBomber
        }
    }

    @Test
    fun testCreatePracticeSessionReqWithDao() {
        val savedBomber = Room.slots[0].bomber
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        val user = User(client, id = 7215, name = "ffdf", bomber = 0)
        client.users.add(user)
        ch.pipeline().addLast(client)

        try {
            // 构造客户端发送的 op 75，角色指定为 0 (皮蛋)
            // 载荷: E2(0) E2(0) E1(0) E1(1) E1(0) E1(15) E4(66)
            val buf = Unpooled.buffer()
            val sendPkt = SendPacketBase(buf, 3)
            sendPkt.Encode2(0) // roomId
            sendPkt.Encode2(0) // mode
            sendPkt.Encode1(0)
            sendPkt.Encode1(1)
            sendPkt.Encode1(0) // bomber = 0 (皮蛋)
            sendPkt.Encode1(15)
            sendPkt.Encode4(66) // mapId

            with(CreatePracticeSessionReq) {
                buf.handle(client)
            }

            assertEquals(0, Room.slots[0].bomber.toInt(), "Room.slots[0].bomber must be updated to 0 (Dao)")
            assertEquals(0, user.bomber, "User.bomber must be updated to 0 (Dao)")

            // 收集所有下发的包
            val sentPackets = mutableListOf<PKT>()
            while (true) {
                val p = ch.readOutbound<PKT>() ?: break
                sentPackets.add(p)
            }

            // 过滤出所有 op 105 (PracticeSetPlayer)
            val op105List = sentPackets.filter { OpcodeManager.getSendOp(it::class.java) == 105 }
            assertEquals(8, op105List.size, "Must send op 105 for all 8 slots")

            // 槽位 0 的 op 105
            val slot0Buf = Unpooled.buffer()
            op105List[0].encode(SendPacketBase(slot0Buf, 3))
            assertEquals(0, slot0Buf.Decode1, "Slot 0 index")
            assertEquals(0, slot0Buf.Decode1, "Slot 0 bomber must be 0 (Dao) matching MyPage selection!")

            // 槽位 1 的 op 105 (AI)
            val slot1Buf = Unpooled.buffer()
            op105List[1].encode(SendPacketBase(slot1Buf, 3))
            assertEquals(1, slot1Buf.Decode1, "Slot 1 index")
            assertEquals(5, slot1Buf.Decode1, "AI bomber must remain 5")
        } finally {
            Room.slots[0].bomber = savedBomber
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testGameStartMapInfoRetainsDaoBomberZero() {
        val savedBomber = Room.slots[0].bomber
        try {
            Room.slots[0].bomber = 0

            val pkt = GameStartMapInfo()
            val buf = Unpooled.buffer()
            pkt.encode(SendPacketBase(buf, 3))

            buf.Decode4 // seed
            buf.Decode2 // theme
            buf.DecodeCipherStr // mapName
            buf.Decode4 // mapId
            buf.Decode1 // gameOptions
            buf.Decode1 // flag
            buf.Decode1 // slotCount

            val idx0 = buf.Decode1
            val spawn0 = buf.Decode1
            val bomber0 = buf.Decode1
            assertEquals(0, idx0)
            assertEquals(0, bomber0, "GameStartMapInfo must retain bomber 0 (Dao) and not clobber it to 5")
        } finally {
            Room.slots[0].bomber = savedBomber
        }
    }
}
