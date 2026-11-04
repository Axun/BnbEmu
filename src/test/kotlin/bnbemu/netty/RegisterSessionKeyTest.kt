package bnbemu.netty

import bnbemu.handler.RegisterSessionKey
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * C→S op 297 = 会话密钥登记（原 Unk_297）。
 * 逆向依据见 handler/RegisterSessionKey.kt 与 tools/rev_op297.py。
 */
class RegisterSessionKeyTest {

    @Test
    fun testOpcodeBinding() {
        assertEquals("RegisterSessionKey", OpcodeManager.recvOps[297])
        assertNotNull(handlerArray[297], "Handler 297 must be registered")
        assertSame(RegisterSessionKey, handlerArray[297])
    }

    @Test
    fun testDecodeRealBody() {
        // 抓包实测 body: E2(4) + 令牌(F0 AE 35 F3) + E1(1)
        // 令牌经角色名变换 → 明文 66 66 64 66 = "ffdf"
        val raw = byteArrayOf(
            0xA5.toByte(), 0x6D.toByte(),
            0xF0.toByte(), 0xAE.toByte(), 0x35.toByte(), 0xF3.toByte(),
            0x5B.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(RegisterSessionKey) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes(), "7B body 必须被完整消费")
    }

    @Test
    fun testEmptyKeyAndShortBodyDoNotThrow() {
        // 空密钥变体: E2(0) + E1(0)
        val empty = Unpooled.wrappedBuffer(byteArrayOf(0xA5.toByte(), 0x69.toByte(), 0x5A.toByte()))
        with(RegisterSessionKey) { empty.handle(ClientSocket()) }
        assertEquals(0, empty.readableBytes())

        // 残包（只有串长）不能抛异常断线
        val short = Unpooled.wrappedBuffer(byteArrayOf(0xA5.toByte(), 0x6D.toByte()))
        with(RegisterSessionKey) { short.handle(ClientSocket()) }
    }
}
