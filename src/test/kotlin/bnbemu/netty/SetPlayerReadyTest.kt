package bnbemu.netty

import bnbemu.domain.PlayerProfile
import bnbemu.handler.room.SetPlayerReadyReq
import bnbemu.packet.room.SetPlayer
import bnbemu.packet.room.SetPlayerReadyResult
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.FLAG_READY
import bnbemu.server.Room
import bnbemu.server.Slot
import bnbemu.server.bomber
import bnbemu.server.color
import bnbemu.server.sendRoomEntryChain
import bnbemu.server.state
import bnbemu.service.PlayerService
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SetPlayerReadyTest {

    private fun encodeBody(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    private fun decodeMeta3(raw3: ByteArray): ByteArray {
        // 与 CA.exe sub_78E290 -> sub_75DAF0 对 3B 的解码完全一致
        val w = (((raw3[0].toInt() and 0xFF) shl 8) or (raw3[1].toInt() and 0xFF)) xor 0xA569
        return byteArrayOf(
            (w and 0xFF).toByte(),
            ((w ushr 8) and 0xFF).toByte(),
            ((raw3[2].toInt() and 0xFF) xor 0x5A).toByte(),
        )
    }

    @Test
    fun testOpcodeBindingsMatchENProtocol() {
        assertEquals(SetPlayerReadyReq, handlerArray[84], "op 84 必须绑定到 SetPlayerReadyReq")
        assertEquals(102, OpcodeManager.getSendOp(SetPlayerReadyResult()::class.java), "SetPlayerReadyResult 必须绑定到 op 102")
    }

    @Test
    fun testRoomEntrySetPlayerOmitsReadyFlagForChief() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(7215, "kcworms1", "ffdf"))
        cs.playerId = user.userId
        ch.pipeline().addLast(cs)

        val room = Room()
        try {
            room.slots[0].assignHuman(user.userId, cs)
            room.slots[0].state = FLAG_CHIEF
            room.slots[0].bomber = 5
            room.slots[0].color = 0

            sendRoomEntryChain(cs, room, init = true)

            val packets = generateSequence { ch.readOutbound<PKT>() }.toList()
            val setPlayer = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 100 }
            assertNotNull(setPlayer, "进房链必须包含 SetPlayer (op 100)")

            val body = encodeBody(setPlayer!!)
            // CSetPlayerPacket: slot(1) + userId(4) + flag16(1) + flag20(1) + flag36(1) + dummy(1) + enterEffect(1) = 10B, 随后 3B 为 raw3
            val meta = decodeMeta3(body.copyOfRange(10, 13))
            assertEquals(FLAG_CHIEF, meta[0].toInt() and 0xFF, "房主初始 state 不得携带 FLAG_READY(0x04)，否则 AutoReady=1 客户端会误发 op 84")
            assertEquals(5, meta[1].toInt() and 0xFF)
            assertEquals(0, meta[2].toInt() and 0xFF)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testCaptureOp84UnreadyRepliedWithOp102UnlockingChief() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(7215, "kcworms1", "ffdf"))
        cs.playerId = user.userId
        ch.pipeline().addLast(cs)

        val room = Room.enter(Room())
        try {
            room.slots[0].assignHuman(user.userId, cs)
            room.slots[0].state = FLAG_CHIEF or FLAG_READY
            room.slots[0].bomber = 5
            room.slots[0].color = 0

            // 用户实测日志中的真实 C→S op 84 载荷: 5A 96 CA 53 95 (ready=0, u32=0)
            val payload = "5A 96 CA 53 95".toBA()
            val buf = Unpooled.wrappedBuffer(payload)
            with(SetPlayerReadyReq) { buf.handle(cs) }
            assertEquals(0, buf.readableBytes(), "op 84 5B 载荷必须被完整消费")
            assertEquals(FLAG_CHIEF, room.slots[0].state.toInt() and 0xFF, "取消准备必须清掉 FLAG_READY 且保留 FLAG_CHIEF")

            val reply = ch.readOutbound<PKT>()
            assertNotNull(reply, "收到 op 84 必须回发 S→C op 102 解锁客户端等待锁 4")
            assertEquals(102, OpcodeManager.getSendOp(reply!!::class.java))

            val replyBody = encodeBody(reply)
            assertEquals(5, replyBody.size)
            assertEquals(0, (replyBody[0].toInt() and 0xFF) xor 0x5A)
            assertArrayEquals(byteArrayOf(FLAG_CHIEF.toByte(), 5, 0), decodeMeta3(replyBody.copyOfRange(1, 4)))
            assertEquals(0, (replyBody[4].toInt() and 0xFF) xor 0x5A)
        } finally {
            Room.leave()
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testNonChief1ByteReadyToggleUpdatesFlagReady() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(8888, "ready_member", "member"))
        cs.playerId = user.userId
        ch.pipeline().addLast(cs)

        val room = Room.enter(Room())
        try {
            room.slots[1].assignHuman(user.userId, cs)
            room.slots[1].state = 0
            room.slots[1].bomber = 3
            room.slots[1].color = 2

            // CSlotStage 只发 1B: E1(1)
            val buf = Unpooled.wrappedBuffer(byteArrayOf((1 xor 0x5A).toByte()))
            with(SetPlayerReadyReq) { buf.handle(cs) }
            assertEquals(0, buf.readableBytes())
            assertEquals(FLAG_READY, room.slots[1].state.toInt() and 0xFF)

            val reply = ch.readOutbound<PKT>()
            assertNotNull(reply, "成员位发 op 84 后必须收到 S→C op 102 广播")
            val replyBody = encodeBody(reply!!)
            assertEquals(1, (replyBody[0].toInt() and 0xFF) xor 0x5A)
            assertArrayEquals(byteArrayOf(FLAG_READY.toByte(), 3, 2), decodeMeta3(replyBody.copyOfRange(1, 4)))
        } finally {
            Room.leave()
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testProgrammaticSlotMetaThreeModes() {
        val tok = bnbemu.CaptureFixtures.CAPTURE_SESSION_TOKEN
        // 1) !isStart, slot == null: 兼容 tst.pcap 抓包基准 (FLAG_CHIEF|FLAG_READY=0x06, Boz=5, color=0)
        val roomNull = encodeBody(SetPlayer(slotId = 0, level = 1, session = tok, isStart = false))
        assertEquals(byteArrayOf(0x06, 0x05, 0x00).toList(), decodeMeta3(roomNull.copyOfRange(10, 13)).toList())

        // 2) isStart == true, slot == null: 对局开局态 (FLAG_READY=0x04, Boz=5, color=0; 无 enterEffectId 故偏移 9..11)
        val startNull = encodeBody(SetPlayer(slotId = 0, level = 1, session = tok, isStart = true))
        assertEquals(byteArrayOf(0x04, 0x05, 0x00).toList(), decodeMeta3(startNull.copyOfRange(9, 12)).toList())

        // 3) !isStart, slot != null: 按槽位真实 meta 构建 (房主仅 0x02, bomber=10, color=3)
        val explicitSlot = Slot(meta = byteArrayOf(FLAG_CHIEF.toByte(), 10, 3))
        val withSlot = encodeBody(SetPlayer(slotId = 0, slot = explicitSlot, level = 1, session = tok, isStart = false))
        assertEquals(byteArrayOf(FLAG_CHIEF.toByte(), 10, 3).toList(), decodeMeta3(withSlot.copyOfRange(10, 13)).toList())

        // 4) isStart == true, slot != null: 开局态清除 FLAG_CHIEF 并置 FLAG_READY，保留槽位角色/颜色
        val startWithSlot = encodeBody(SetPlayer(slotId = 0, slot = explicitSlot, level = 1, session = tok, isStart = true))
        assertEquals(byteArrayOf(FLAG_READY.toByte(), 10, 3).toList(), decodeMeta3(startWithSlot.copyOfRange(9, 12)).toList())
    }
}
