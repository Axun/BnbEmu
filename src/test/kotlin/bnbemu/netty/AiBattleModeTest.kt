package bnbemu.netty

import bnbemu.handler.CreateSessionReq
import bnbemu.handler.JoinFreeSessionReq
import bnbemu.handler.room.AILevelChangeReq
import bnbemu.handler.room.NpcEventReq
import bnbemu.handler.room.OpenCloseSlotReq
import bnbemu.handler.room.ShopToRoomReq
import bnbemu.handler.room.StartGameReq
import bnbemu.packet.ClientStage
import bnbemu.packet.EnterSessionResult
import bnbemu.packet.JOIN_FREE_NO_ROOM
import bnbemu.packet.JoinFreeSessionResult
import bnbemu.packet.NpcAvailability
import bnbemu.packet.NpcEvent
import bnbemu.packet.PracticeSetSlotAI
import bnbemu.packet.RoomList
import bnbemu.packet.SESSION_TYPE_BATTLE
import bnbemu.packet.SESSION_TYPE_PRACTICE
import bnbemu.packet.SetSlotColor
import bnbemu.packet.SetSlotFanfare
import bnbemu.packet.room.AI_LEVEL_TO_LEVEL
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.packet.room.SlotBomber
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.FLAG_CLOSED
import bnbemu.server.Room
import bnbemu.server.User
import bnbemu.server.bomber
import bnbemu.server.color
import bnbemu.server.state
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AiBattleModeTest {

    private var savedMode = 0
    private var savedType = SESSION_TYPE_PRACTICE
    private var savedRuleId = 66
    private var savedName = ""
    private var savedPassword = ""
    private var savedMapId = 89
    private var savedThemeId = 0

    @BeforeEach
    fun setUp() {
        savedMode = Room.sessionMode
        savedType = Room.sessionType
        savedRuleId = Room.ruleId
        savedName = Room.name
        savedPassword = Room.password
        savedMapId = Room.mapId
        savedThemeId = Room.themeId
    }

    @AfterEach
    fun tearDown() {
        Room.reset()
        Room.sessionMode = savedMode
        Room.sessionType = savedType
        Room.ruleId = savedRuleId
        Room.name = savedName
        Room.password = savedPassword
        Room.mapId = savedMapId
        Room.themeId = savedThemeId
        Room.slots.forEach {
            it.user = null
            it.isAI = false
            it.state = 0
            it.aILevel = 2
            it.bomber = 0
            it.color = 0
        }
    }

    private fun newClient(bomber: Int = 0): Pair<EmbeddedChannel, ClientSocket> {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)
        val user = User(c = client, id = 7215, name = "tester", bomber = bomber)
        client.users.add(user)
        return ch to client
    }

    private fun drainOutbound(ch: EmbeddedChannel): List<Pair<Int, ByteBuf>> {
        val result = mutableListOf<Pair<Int, ByteBuf>>()
        while (true) {
            val pkt = ch.readOutbound<PKT>() ?: break
            val buf = Unpooled.buffer()
            pkt.encode(SendPacketBase(buf, 3))
            val op = OpcodeManager.getSendOp(pkt::class.java)
            result += op to buf
        }
        return result
    }

    private fun encodePacket(block: SendPacketBase.() -> Unit): ByteBuf {
        val buf = Unpooled.buffer()
        SendPacketBase(buf, 3).block()
        return buf
    }

    @Test
    fun `test opcodes and difficulty badge mapping`() {
        assertEquals(270, OpcodeManager.getSendOp(NpcEvent(4, 1, 2)::class.java))
        assertEquals(271, OpcodeManager.getSendOp(NpcAvailability()::class.java))
        assertEquals(84, OpcodeManager.getSendOp(JoinFreeSessionResult()::class.java))
        assertEquals(104, OpcodeManager.getSendOp(SetSlotColor(0)::class.java))
        assertEquals(114, OpcodeManager.getSendOp(SetSlotFanfare(0)::class.java))
        assertEquals("NpcEventReq", OpcodeManager.recvOps[156])
        assertEquals(NpcEventReq, handlerArray[156])

        assertEquals(3, AI_LEVEL_TO_LEVEL[0])
        assertEquals(10, AI_LEVEL_TO_LEVEL[1])
        assertEquals(52, AI_LEVEL_TO_LEVEL[2])
        assertEquals(109, AI_LEVEL_TO_LEVEL[3])
        assertEquals(163, AI_LEVEL_TO_LEVEL[4])
    }

    @Test
    fun `test JoinFreeSessionReq returns no room and JoinFreeSessionResult supports result 0`() {
        val (ch, client) = newClient(bomber = 2)

        val req = encodePacket {
            Encode1(1)
            Encode1(2)
            Encode2(100)
            Encode2(0)
        }
        with(JoinFreeSessionReq) { req.handle(client) }
        val rejected = drainOutbound(ch)
        assertEquals(listOf(84), rejected.map { it.first })
        assertEquals(JOIN_FREE_NO_ROOM, rejected[0].second.Decode1)
        assertEquals(0, rejected[0].second.readableBytes())
        assertNotEquals(ClientStage.Room.value, client.stage)

        // Verify JoinFreeSessionResult(result=0) payload encoding
        val okBuf = encodePacket {
            // reuse encodePacket only for scratch; encode PKT directly below
        }
        okBuf.release()
        val buf = Unpooled.buffer()
        JoinFreeSessionResult(
            result = 0,
            session = 75,
            seq = 123,
            sessionType = SESSION_TYPE_BATTLE,
            state = 1,
        ).encode(SendPacketBase(buf, 3))
        assertEquals(0, buf.Decode1)
        assertEquals(75, buf.Decode2)
        assertEquals(123, buf.Decode4)
        assertEquals(SESSION_TYPE_BATTLE, buf.Decode1)
        assertEquals(1, buf.Decode1)
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun `test CreateSessionReq sessionType 16 sends NpcAvailability and clean slot 103 refSlot`() {
        val (ch, client) = newClient(bomber = 0)
        val createReq = encodePacket {
            EncodeCipherStr("My AI Battle")
            EncodeCipherStr("")
            Encode1(0)
            Encode1(1)
            Encode1(1)
            Encode1(SESSION_TYPE_BATTLE)
            Encode4(2)
        }
        with(CreateSessionReq) { createReq.handle(client) }

        val packets = drainOutbound(ch)
        val ops = packets.map { it.first }
        assertTrue(83 in ops)
        assertTrue(271 in ops)
        assertEquals(SESSION_TYPE_BATTLE, Room.sessionType)
        assertEquals(1, Room.slots[0].bomber.toInt())

        // PracticeSetSlotAI (103) must only target NPC slots 4..7 and reference slot 0
        val aiPackets = packets.filter { it.first == 103 }
        assertEquals(4, aiPackets.size)
        for ((idx, pair) in aiPackets.withIndex()) {
            val b = pair.second
            val slot = b.Decode2
            val isAI = b.Decode1 != 0
            val refSlot = b.Decode2
            val aiLevel = b.Decode2
            assertEquals(4 + idx, slot)
            assertTrue(isAI)
            assertEquals(0, refSlot)
            assertEquals(Room.slots[slot].aILevel, aiLevel)
        }
    }

    @Test
    fun `test AILevelChangeReq and OpenCloseSlotReq in AI battle`() {
        val (ch, client) = newClient(bomber = 0)
        Room.sessionType = SESSION_TYPE_BATTLE
        Room.setupAiBattleSlots(client.users.first())

        // Change slot 5 to NPC 23 and difficulty 4
        val changeReq = encodePacket {
            Encode1(0)  // chief
            Encode1(5)  // slotIdx
            Encode1(23) // charId
            Encode1(4)  // aiLevel
        }
        with(AILevelChangeReq) { changeReq.handle(client) }
        val changed = drainOutbound(ch)
        assertEquals(listOf(105, 103), changed.map { it.first })
        assertEquals(5, changed[0].second.Decode1)
        assertEquals(23, changed[0].second.Decode1)
        assertEquals(5, changed[1].second.Decode2)
        assertTrue(changed[1].second.Decode1 != 0)
        assertEquals(0, changed[1].second.Decode2) // refSlot = chief (0)
        assertEquals(4, changed[1].second.Decode2) // aiLevel = 4
        assertEquals(23, Room.slots[5].bomber.toInt())
        assertEquals(4, Room.slots[5].aILevel)

        // Human slot 2 in mode 16 can be opened (waiting for human player, NO AI) and closed via op 89
        val humanOpen = encodePacket { Encode1((2 shl 4) or 0) }
        with(OpenCloseSlotReq) { humanOpen.handle(client) }
        val openedHumanPkts = drainOutbound(ch)
        assertEquals(listOf(106), openedHumanPkts.map { it.first })
        assertEquals((2 shl 4) or 2, openedHumanPkts[0].second.Decode1)
        assertFalse(Room.slots[2].isAI)
        assertEquals(0, Room.slots[2].state.toInt())

        val humanClose = encodePacket { Encode1((2 shl 4) or 1) }
        with(OpenCloseSlotReq) { humanClose.handle(client) }
        val closedHumanPkts = drainOutbound(ch)
        assertEquals(listOf(106), closedHumanPkts.map { it.first })
        assertEquals((2 shl 4) or 1, closedHumanPkts[0].second.Decode1)
        assertFalse(Room.slots[2].isAI)
        assertEquals(FLAG_CLOSED, Room.slots[2].state.toInt())

        // Close NPC slot 5, then reopen it: keeps NPC bomber 23 and difficulty 4
        val closeSlot5 = encodePacket { Encode1((5 shl 4) or 1) }
        with(OpenCloseSlotReq) { closeSlot5.handle(client) }
        val closedPkts = drainOutbound(ch)
        assertEquals(listOf(106, 103), closedPkts.map { it.first })
        assertFalse(Room.slots[5].isAI)
        assertEquals(FLAG_CLOSED, Room.slots[5].state.toInt())

        val reopenSlot5 = encodePacket { Encode1((5 shl 4) or 0) }
        with(OpenCloseSlotReq) { reopenSlot5.handle(client) }
        val reopenedPkts = drainOutbound(ch)
        assertEquals(listOf(106, 114, 104, 105, 103), reopenedPkts.map { it.first })
        assertTrue(Room.slots[5].isAI)
        assertEquals(23, Room.slots[5].bomber.toInt())
        assertEquals(4, Room.slots[5].aILevel)
        assertEquals(5, reopenedPkts[3].second.Decode1)
        assertEquals(23, reopenedPkts[3].second.Decode1)
    }

    @Test
    fun `test NpcEventReq echoes NpcEvent and ignores malformed payloads`() {
        val (ch, client) = newClient(bomber = 0)
        Room.sessionType = SESSION_TYPE_BATTLE
        Room.setupAiBattleSlots(client.users.first())

        val firstEvt = encodePacket {
            Encode2(4)
            Encode2(2)
            Encode2(99)
        }
        with(NpcEventReq) { firstEvt.handle(client) }
        val out1 = drainOutbound(ch)
        assertEquals(listOf(270), out1.map { it.first })

        val evtBuf = out1[0].second
        assertEquals(4, evtBuf.Decode2)
        assertEquals(2, evtBuf.Decode2)
        assertEquals(99, evtBuf.Decode2)

        val secondEvt = encodePacket {
            Encode2(6)
            Encode2(1)
            Encode2(0)
        }
        with(NpcEventReq) { secondEvt.handle(client) }
        val out2 = drainOutbound(ch)
        assertEquals(listOf(270), out2.map { it.first })

        // Non-6B op 156 must be safely consumed without throwing
        val malformed = encodePacket {
            Encode1(2)
            Encode1(1)
        }
        with(NpcEventReq) { malformed.handle(client) }
        assertTrue(drainOutbound(ch).isEmpty())
    }

    @Test
    fun `test StartGameReq and backToRoom ShopToRoomReq round trip for AI battle`() {
        val (ch, client) = newClient(bomber = 0)
        Room.sessionType = SESSION_TYPE_BATTLE
        Room.setupAiBattleSlots(client.users.first())
        Room.slots[4].bomber = 21
        Room.slots[4].aILevel = 3

        // 激战模式 (sessionType=16) CA.exe sub_531A09 不写 fileCheckType，载荷为 16B:
        // mapId(4) + themeId(2) + subType(1) + roomSelectedMapId(4) + isCustomMap(1) + eventParam(4)
        // 此处按真实抓包日志同构格式构造 16B (mapId=268, themeId=570 -> CA.exe 实发)
        val validMapId = 268
        val startReq = encodePacket {
            Encode4(validMapId)
            Encode2(570)
            Encode1(0)
            Encode4(validMapId)
            Encode1(0)
            Encode4(0)
        }
        with(StartGameReq) { startReq.handle(client) }
        val startPkts = drainOutbound(ch)
        // StartGameReq responds with [111, 112, 100, 136]
        val startIdx112 = startPkts.indexOfFirst { it.first == 112 }
        assertTrue(startIdx112 >= 0)
        val buf112 = startPkts[startIdx112].second
        buf112.Decode4 // seed
        buf112.Decode2 // theme
        buf112.DecodeCipherStr // mapName
        buf112.Decode4 // mapId
        assertEquals(SESSION_TYPE_BATTLE, buf112.Decode1, "gameOptions must be 16 for AI battle")
        buf112.Decode1 // flag
        val rosterCount = buf112.Decode1
        assertEquals(5, rosterCount, "Slots 0,4,5,6,7 participate")
        // Verify slot 0 team=0, slot 4 bomber=21 team=8
        assertEquals(0, buf112.Decode1)
        buf112.Decode1 // spawn
        buf112.Decode1 // bomber 0
        assertEquals(0, buf112.Decode1) // team 0
        buf112.Decode1; buf112.Decode4; buf112.Decode4; buf112.Decode4

        assertEquals(4, buf112.Decode1)
        buf112.Decode1 // spawn
        assertEquals(21, buf112.Decode1) // bomber 21
        assertEquals(8, buf112.Decode1) // team 8

        // 直接用报错日志中的 16B 原始载荷验证不再抛出 IndexOutOfBoundsException
        val capturedWireBytes = byteArrayOf(
            0x96.toByte(), 0xCA.toByte(), 0x52, 0x99.toByte(),
            0xA7.toByte(), 0x53,
            0x5A,
            0x96.toByte(), 0xCA.toByte(), 0x52, 0x99.toByte(),
            0x5A,
            0x96.toByte(), 0xCA.toByte(), 0x53, 0x95.toByte(),
        )
        val rawBuf = Unpooled.wrappedBuffer(capturedWireBytes)
        with(StartGameReq) { rawBuf.handle(client) }
        val wireOut = drainOutbound(ch)
        assertTrue(wireOut.any { it.first == 112 })
        assertEquals(0, rawBuf.readableBytes())

        // backToRoom + ShopToRoomReq preserve AI Battle state & NpcAvailability
        Room.backToRoom(force = true)
        assertNull(Room.battle, "backToRoom 必须销毁 BattleSession")
        val backOps = drainOutbound(ch).map { it.first }
        assertTrue(155 in backOps)
        assertTrue(271 in backOps)

        val shopReturnReq = encodePacket {
            Encode2(Room.sessionId)
            Encode4(Room.sessionId)
        }
        with(ShopToRoomReq) { shopReturnReq.handle(client) }
        val shopOps = drainOutbound(ch).map { it.first }
        assertTrue(271 in shopOps)
        assertEquals(21, Room.slots[4].bomber.toInt())
        assertEquals(3, Room.slots[4].aILevel)
        assertEquals(FLAG_CLOSED, Room.slots[1].state.toInt())
    }
}
