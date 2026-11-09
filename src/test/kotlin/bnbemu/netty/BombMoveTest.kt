package bnbemu.netty

import bnbemu.handler.room.BombKickThrowReq
import bnbemu.packet.room.BombKickThrow
import bnbemu.server.Bomb
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

class BombMoveTest {

    @Test
    fun testOpcodeBinding() {
        // op 107 不再是 TW 表误名的 ChangeTitleReq, 是「水泡移动(抛掷/踢出)请求」
        assertEquals("BombKickThrowReq", OpcodeManager.recvOps[107])
        assertNotNull(handlerArray[107], "Handler 107 must be registered")
        assertSame(BombKickThrowReq, handlerArray[107])
    }

    @Test
    fun testDecodeRealKickBody() {
        // 用户日志实测样本 (2026-11-09 踢水泡):
        //   kind=6 (踢出/滑行, 方向=kind-4=2 → gridY+1) slot=0 bombId=20 (2,3)→(2,4) ms=1000
        val raw = byteArrayOf(
            0x3A.toByte(), 0xA5.toByte(), 0x7D.toByte(),
            0x58.toByte(), 0x59.toByte(), 0x58.toByte(), 0x5E.toByte(),
            0xA6.toByte(), 0x81.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(BombKickThrowReq) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes(), "9B body must be fully consumed")
        val state = Room.bombMoveState(20)
        assertNotNull(state)
        state!!
        assertEquals(6, state.kind)
        assertEquals(20, state.bombId)
        assertEquals(2, state.fromX)
        assertEquals(3, state.fromY)
        assertEquals(2, state.toX)
        assertEquals(4, state.toY)
    }

    @Test
    fun testDecodeTouchPushBody() {
        // rev_op107.py 实测样本 B: kind=0 (触碰推行) bombId=250 (7,9)→(7,11) ms=1790
        val raw = byteArrayOf(
            0x5A.toByte(), 0xA5.toByte(), 0x93.toByte(),
            0x5D.toByte(), 0x53.toByte(), 0x5D.toByte(), 0x51.toByte(),
            0xA3.toByte(), 0x97.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)
        with(BombKickThrowReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes())
        val state = Room.bombMoveState(250)
        assertNotNull(state)
        state!!
        assertEquals(0, state.kind)
        assertEquals(7, state.fromX)
        assertEquals(9, state.fromY)
        assertEquals(7, state.toX)
        assertEquals(11, state.toY)
    }

    @Test
    fun testShortBodyDoesNotThrow() {
        val buf = Unpooled.wrappedBuffer(
            byteArrayOf(0x3A.toByte(), 0xA5.toByte(), 0x7D.toByte())
        )
        with(BombKickThrowReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes(), "残包必须被跳过, 不能抛异常断线")
    }

    // ---- S→C op 141 BombIgnite : 水泡位移的唯一驱动 ----

    @Test
    fun testEchoBroadcastOpcodeBinding() {
        val op = OpcodeManager.getSendOp(
            BombKickThrow(slotId = 0, bombId = 20, x = 2, y = 4, oldX = 2, oldY = 3, action = 6)::class.java
        )
        assertEquals(
            141, op,
            "CA.exe 0x55A4D8 分发器 141 → 0x54BD14 → 0x4598AB (水泡唯一滑行实现), " +
                "action=kind: 4..7 滑行(方向=action-4) / 0..3 抛掷落地"
        )
    }

    @Test
    fun testEchoEncodingIsOp107BodyMinusMs() {
        // 回显载荷必须与 C→S op 107 实测 body 去掉末尾 E2(ms) 的前 7 字节逐字节一致
        val buf = Unpooled.buffer()
        BombKickThrow(slotId = 0, bombId = 20, x = 2, y = 4, oldX = 2, oldY = 3, action = 6)
            .encode(SendPacketBase(buf, 3))

        val actual = ByteArray(buf.readableBytes())
        buf.readBytes(actual)

        val expected = byteArrayOf(
            0x3A.toByte(),             // E1((kind=6<<4)|slot=0)  = op107 字节 0 原样
            0xA5.toByte(), 0x7D.toByte(), // E2(20) bombId          = 字节 1-2 原样
            0x58.toByte(),             // E1(2) oldX (=fromX)      = 字节 3 原样 (客户端与 bomb+0x9C 校验)
            0x59.toByte(),             // E1(3) oldY (=fromY)      = 字节 4 原样
            0x58.toByte(),             // E1(2) x     (=toX)       = 字节 5 原样 (sub_4598AB 写 bomb+0x9C)
            0x5E.toByte()              // E1(4) y     (=toY)       = 字节 6 原样 (写 bomb+0xA8)
        )
        assertEquals(
            expected.toHexString(), actual.toHexString(),
            "op141 回显 = op107 实测 9B 去掉末尾 E2(1000) 的前 7 字节原样"
        )
    }

    @Test
    fun testMoveIsBroadcastToConnectedClients() {
        // 端到端: C→S op 107 → Room.onBombMove → BC → 客户端 ctx 真的收到 S→C op 141
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val previous = Room.slots[0].user
        try {
            Room.slots[0].user = User(cs, id = 1, name = "tester")
            Room.reset()
            ch.outboundMessages().clear()

            assertTrue(Room.onBombMove(0, 6, 20, 2, 3, 2, 4, 1000), "首次踢泡必须广播")

            val sent = ch.outboundMessages().toList()
            assertEquals(1, sent.size, "一次踢泡动作必须恰好下发一包 S→C op 141")
            val buf = Unpooled.buffer()
            (sent[0] as PKT).encode(SendPacketBase(buf, 3))
            val actual = ByteArray(buf.readableBytes())
            buf.readBytes(actual)
            assertEquals(
                byteArrayOf(
                    0x3A.toByte(), 0xA5.toByte(), 0x7D.toByte(), 0x58.toByte(),
                    0x59.toByte(), 0x58.toByte(), 0x5E.toByte()
                ).toHexString(),
                actual.toHexString(),
                "广播包体必须等于 op107 实测 body 前 7 字节 (3A A5 7D 58 59 58 5E)"
            )
        } finally {
            Room.slots[0].user = previous
            Room.reset()
            ch.finish()
        }
    }

    @Test
    fun testBombStateUpdatedOnMove() {
        try {
            Room.reset()
            Room.bombs += Bomb(id = 20, ownerSlotId = 0, pos = 0, bombAttr = 0, isSpecial = false).apply {
                gridX = 2
                gridY = 3
            }

            Room.onBombMove(0, 6, 20, 2, 3, 2, 4, 1000)

            val bomb = Room.getBomb(20)
            assertNotNull(bomb)
            bomb!!
            assertEquals(2, bomb.gridX, "服务端水泡位置必须跟进到目标格")
            assertEquals(4, bomb.gridY)
            assertTrue(bomb.isMoving, "滑行期间 isMoving=true, 到点由 Bomb.updateState 续时再引爆")
        } finally {
            Room.reset()
        }
    }

    @Test
    fun testDuplicateMoveIsNotRebroadcast() {
        val first = Room.onBombMove(0, 6, 30, 1, 1, 1, 2, 1000)
        val dup = Room.onBombMove(0, 6, 30, 1, 1, 1, 2, 1000)
        assertTrue(first, "首次踢泡必须广播")
        assertFalse(dup, "CGameMsgCache 帧级重发的同一动作不能重复广播")
        // 踢回同格以外的新动作照常广播
        assertTrue(Room.onBombMove(0, 6, 30, 1, 2, 1, 3, 1000))
    }

    @Test
    fun testDuplicateRebroadcastAfterWindow() {
        val window = Room.bombMoveDedupeWindowMs
        try {
            Room.bombMoveDedupeWindowMs = 0
            assertTrue(Room.onBombMove(1, 4, 31, 5, 5, 5, 3, 1000))
            assertTrue(
                Room.onBombMove(1, 4, 31, 5, 5, 5, 3, 1000),
                "窗口过后同一动作必须能再次广播 (漏包自愈)"
            )
        } finally {
            Room.bombMoveDedupeWindowMs = window
        }
    }
}
