package bnbemu.netty

import bnbemu.handler.room.ThrowDartReq
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ThrowDartReqTest {

    @Test
    fun testOpcodeBinding() {
        assertEquals("ThrowDartReq", OpcodeManager.recvOps[121])
        assertNotNull(handlerArray[121], "Handler 121 must be registered")
        assertSame(ThrowDartReq, handlerArray[121])
    }

    @Test
    fun testDecodeRealDartBody() {
        // 用户日志实测样本 (2026-11-14 发射飞镖):
        //   E1(0) E2(40) E2(480) E1(1) E2(40) → 起点 (40,480)px ≈ 格 (1,11), 方向 1, 射程 1 格
        val raw = byteArrayOf(
            0x5A.toByte(), 0xA5.toByte(), 0x41.toByte(), 0xA4.toByte(),
            0x89.toByte(), 0x5B.toByte(), 0xA5.toByte(), 0x41.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(ThrowDartReq) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes(), "8B body must be fully consumed (空消费: 不回包)")
    }

    @Test
    fun testShortBodyDiscarded() {
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte(), 0xA5.toByte(), 0x41.toByte()))
        with(ThrowDartReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes(), "残包必须被跳过, 不能抛异常断线")
    }

    @Test
    fun testExtraBytesDiscarded() {
        val raw = byteArrayOf(
            0x5A.toByte(), 0xA5.toByte(), 0x41.toByte(), 0xA4.toByte(),
            0x89.toByte(), 0x5B.toByte(), 0xA5.toByte(), 0x41.toByte(),
            0x5A.toByte(), 0x5A.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)
        with(ThrowDartReq) {
            buf.handle(ClientSocket())
        }
        assertEquals(0, buf.readableBytes(), "追加字段不得残留影响下一包解析")
    }
}
