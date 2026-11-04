package bnbemu.netty

import bnbemu.handler.room.PushMovableBoxReq
import bnbemu.packet.room.MovableBoxMove
import bnbemu.server.Room
import bnbemu.server.User
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushMovableBoxTest {

    @Test
    fun testOpcodeBinding() {
        // op 109 不再是旧表误标的 ReadyForImmediateStartReq
        assertEquals("PushMovableBoxReq", OpcodeManager.recvOps[109])
        assertNotNull(handlerArray[109], "Handler 109 must be registered")
        assertSame(PushMovableBoxReq, handlerArray[109])
    }

    @Test
    fun testDecodeRealPushBoxBody() {
        // 用户日志实测样本: E2(1) E1(1) E1(3) E1(1) E1(4) E1(0)
        val raw = byteArrayOf(
            0xA5.toByte(), 0x68.toByte(),
            0x5B.toByte(), 0x59.toByte(), 0x5B.toByte(), 0x5E.toByte(), 0x5A.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(PushMovableBoxReq) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes(), "7B body must be fully consumed")
        val state = Room.movableBoxState(1)
        assertNotNull(state)
        state!!
        assertEquals(1, state.gridX)
        assertEquals(3, state.gridY)
        assertEquals(1, state.targetX)
        assertEquals(4, state.targetY)
        assertEquals(0, state.slotId)
    }

    @Test
    fun testShortBodyDoesNotThrow() {
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0xA5.toByte(), 0x68.toByte(), 0x5B.toByte()))
        with(PushMovableBoxReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes(), "残包必须被跳过, 不能抛异常断线")
    }

    // ---- S→C op 142 MovableBoxMove : 箱子位移广播 ----

    @Test
    fun testMoveBroadcastOpcodeBinding() {
        val op = OpcodeManager.getSendOp(
            MovableBoxMove(1, 1, 3, 1, 4, 0)::class.java
        )
        assertEquals(
            142, op,
            "CA.exe 0x55A4D8 分发器 142 → 0x54A5F9 → 0x565CE0 (箱子唯一移动实现), " +
                "旧 TW_5 的 155/357 在 EN 客户端无分支"
        )
    }

    @Test
    fun testPushIsBroadcastToConnectedClients() {
        // 端到端: C→S op 109 → Room.onMovableBoxPush → BC → 客户端 ctx 真的收到 S→C op 142
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val previous = Room.slots[0].user
        try {
            Room.slots[0].user = User(cs, id = 1, name = "tester")
            Room.reset()
            ch.outboundMessages().clear()

            assertTrue(Room.onMovableBoxPush(1, 1, 3, 1, 4, 0), "首次推动必须广播")

            val sent = ch.outboundMessages().toList()
            assertEquals(1, sent.size, "一次推箱动作必须恰好下发一包 S→C op 142")
            val buf = Unpooled.buffer()
            (sent[0] as PKT).encode(SendPacketBase(buf, 3))
            val actual = ByteArray(buf.readableBytes())
            buf.readBytes(actual)
            assertEquals(
                byteArrayOf(
                    0xA5.toByte(), 0x68.toByte(), 0x5B.toByte(), 0x59.toByte(),
                    0x5B.toByte(), 0x5E.toByte(), 0x5A.toByte()
                ).toHexString(),
                actual.toHexString(),
                "广播包体必须与客户端上报的 7B 完全一致"
            )

            // 同一动作的重发 (OnTick 每 >5 帧再发一次) 不能再广播
            assertFalse(Room.onMovableBoxPush(1, 1, 3, 1, 4, 0))
            assertEquals(1, ch.outboundMessages().size, "重复上报不得再次下发")
        } finally {
            Room.slots[0].user = previous
            Room.reset()
            ch.finish()
        }
    }

    @Test
    fun testMoveBroadcastEncodingMatchesPushBody() {
        // 广播载荷必须与 C→S op 109 的实测 body 逐字节同构 (同一 7B 原样回放)
        val buf = Unpooled.buffer()
        MovableBoxMove(boxSeq = 1, gridX = 1, gridY = 3, targetX = 1, targetY = 4, slotId = 0)
            .encode(SendPacketBase(buf, 3))

        val actual = ByteArray(buf.readableBytes())
        buf.readBytes(actual)

        val expected = byteArrayOf(
            0xA5.toByte(), 0x68.toByte(), // E2(1)  boxSeq  (0x0001 xor 0xA569 = 0xA568)
            0x5B.toByte(),                // E1(1)  gridX
            0x59.toByte(),                // E1(3)  gridY
            0x5B.toByte(),                // E1(1)  targetX
            0x5E.toByte(),                // E1(4)  targetY
            0x5A.toByte()                 // E1(0)  slotId
        )
        assertEquals(
            expected.toHexString(), actual.toHexString(),
            "MovableBoxMove(1,1,3,1,4,0) 必须与 op109 实测 7B 一致"
        )
    }

    @Test
    fun testDuplicatePushIsNotRebroadcast() {
        val first = Room.onMovableBoxPush(9, 2, 2, 2, 3, 0)
        val dup = Room.onMovableBoxPush(9, 2, 2, 2, 3, 0)
        assertTrue(first, "首次推动必须广播")
        assertFalse(dup, "持续推箱每 >5 帧重发的同一动作不能重复广播, 否则客户端校验失败回滚")

        // 箱子继续往前推: src 变成上一次的 dst, 必须广播
        assertTrue(Room.onMovableBoxPush(9, 2, 3, 2, 4, 0), "连续推动的下一步必须广播")
        // 推回去再推回来 (src/dst 完全不同) 也不能被误判为重复
        assertTrue(Room.onMovableBoxPush(9, 2, 4, 2, 3, 0))
        assertTrue(Room.onMovableBoxPush(9, 2, 3, 2, 4, 0))

        val state = Room.movableBoxState(9)
        assertNotNull(state)
        assertEquals(2, state!!.targetX)
        assertEquals(4, state.targetY)
    }

    @Test
    fun testDuplicateRebroadcastAfterWindow() {
        // 去重必须有时间窗: 某端漏包导致箱子没动时, 玩家再推一次要能重播自愈,
        // 否则那个箱子会永久卡死 (客户端 0x565CE0 校验 src 失败后不会再自行纠正)。
        val window = Room.movableBoxDedupeWindowMs
        try {
            Room.movableBoxDedupeWindowMs = 0
            assertTrue(Room.onMovableBoxPush(11, 4, 4, 4, 5, 1))
            assertTrue(
                Room.onMovableBoxPush(11, 4, 4, 4, 5, 1),
                "窗口过后同一动作必须能再次广播"
            )
        } finally {
            Room.movableBoxDedupeWindowMs = window
        }
    }

    @Test
    fun testIllegalBoxSeqIsDropped() {
        assertFalse(Room.onMovableBoxPush(0x10000, 1, 1, 1, 2, 0), "越界 boxSeq 不应广播")
        assertFalse(Room.onMovableBoxPush(3, 1, 1, 1, 2, 0x100), "越界 slotId 不应广播")
        // 末尾字节是客户端待确认队列下标(0..7 之外也不该被丢掉, 接收端根本不使用它)
        assertTrue(Room.onMovableBoxPush(3, 1, 1, 1, 2, 0xFF), "0xFF 以内的末尾字节应放行")
    }
}
