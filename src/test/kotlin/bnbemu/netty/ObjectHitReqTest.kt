package bnbemu.netty

import bnbemu.handler.room.ObjectHitReq
import bnbemu.server.Room
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObjectHitReqTest {

    @BeforeEach
    fun clearBombs() {
        // handler 现在会走 Room.onObjectHit, 隔离其它测试留下的水泡
        synchronized(Room.bombs) { Room.bombs.clear() }
    }

    @Test
    fun testOpcodeBinding() {
        // 旧名 PlayEmotionReq: 108 已注释, 新条目 ObjectHitReq: 108 生效
        assertEquals("ObjectHitReq", OpcodeManager.recvOps[108])
        assertNotNull(handlerArray[108], "Handler 108 must be registered")
        assertSame(ObjectHitReq, handlerArray[108])
    }

    @Test
    fun testDecodeRealHitBody() {
        // 用户日志实测样本 (2026-11-14 15:50:20 飞镖命中):
        //   E2(540) E1(6) E1(5) E2(96) → objId=540 grid=(6,5) delay=96ms ≈ 2 格射程
        val raw = byteArrayOf(
            0xA7.toByte(), 0x75.toByte(), 0x5C.toByte(),
            0x5F.toByte(), 0xA5.toByte(), 0x09.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(ObjectHitReq) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes(), "6B body must be fully consumed (空消费: 不回包)")
    }

    @Test
    fun testShortBodyDiscarded() {
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0xA7.toByte(), 0x75.toByte(), 0x5C.toByte()))
        with(ObjectHitReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes(), "残包必须被跳过, 不能抛异常断线")
    }

    @Test
    fun testExtraBytesDiscarded() {
        val raw = byteArrayOf(
            0xA7.toByte(), 0x75.toByte(), 0x5C.toByte(),
            0x5F.toByte(), 0xA5.toByte(), 0x09.toByte(),
            0x5A.toByte(), 0x5A.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)
        with(ObjectHitReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes(), "追加字段不得残留影响下一包解析")
    }
}
