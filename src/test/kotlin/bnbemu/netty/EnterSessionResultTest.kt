package bnbemu.netty

import bnbemu.packet.SESSION_ENTER_CANT_JOIN
import bnbemu.packet.SESSION_TYPE_NORMAL
import bnbemu.packet.SESSION_TYPE_PRACTICE
import bnbemu.packet.EnterSessionResult
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * op 83 「进房结果」改为程序化生成后的回归基准。
 *
 * 字段来源是客户端处理链的反编译结果 (见 packet/EnterSessionResult.kt 顶部注释):
 *   E1 结果码 + E1 flag + E2 房号 + E1 房型 + E1 state
 * 抓包 tst.pcap stream4 sq=190 的 body = 5a 5a a5 22 55 5a (0, 0, 75, 15, 0)。
 */
class EnterSessionResultTest {

    private fun encode(pkt: bnbemu.netty.PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    @Test
    fun `默认练习房参数与抓包逐字节一致`() {
        val actual = encode(EnterSessionResult(sessionId = 75))
        assertArrayEquals(
            byteArrayOf(0x5A, 0x5A, 0xA5.toByte(), 0x22, 0x55, 0x5A), actual,
            "body 必须 = E1(0) E1(0) E2(75) E1(15) E1(0)"
        )
        assertEquals(6, actual.size, "op 83 成功分支的 body 恒为 6 字节")
    }

    @Test
    fun `cap 基准逐字节一致(去掉 2B opcode)`() {
        val cap = File("cap/practice_s2c_op83_8B_0.bin").takeIf { it.isFile } ?: return
        val raw = cap.readBytes()
        assertEquals(8, raw.size)
        assertEquals(0xA5.toByte(), raw[0])
        assertEquals(0x3A.toByte(), raw[1], "0xA53A ^ 0xA569 = 83")
        assertArrayEquals(raw.copyOfRange(2, 8), encode(EnterSessionResult(sessionId = 75)))
    }

    @Test
    fun `房号与房型由参数决定`() {
        val actual = encode(EnterSessionResult(sessionId = 61, sessionType = 14, state = 3))
        // E1(0) E1(0) E2(61) E1(14) E1(3)
        assertArrayEquals(
            byteArrayOf(
                0x5A, 0x5A,
                (0xA569 xor 61).ushr(8).toByte(), (0xA569 xor 61).toByte(),
                (0x5A xor 14).toByte(), (0x5A xor 3).toByte(),
            ), actual
        )
    }

    @Test
    fun `失败结果码只占 1 字节且后面不再带字段`() {
        val actual = encode(EnterSessionResult(sessionId = 75, result = SESSION_ENTER_CANT_JOIN, flag = 0, state = 0))
        // 客户端 case 1..12 只弹对话框、不读后续字段; 这里仍按 CAPTURE 形态发完整 6 字节,
        // 但结果码必须落在第 1 字节
        assertEquals((0x5A xor SESSION_ENTER_CANT_JOIN).toByte(), actual[0])
    }

    @Test
    fun `练习房房型常量为 15`() {
        assertEquals(15, SESSION_TYPE_PRACTICE)
    }

    @Test
    fun `普通房房型 0 合法且原样回显`() {
        assertEquals(0, SESSION_TYPE_NORMAL)
        val actual = encode(EnterSessionResult(sessionId = 75, sessionType = SESSION_TYPE_NORMAL))
        // E1(0) E1(0) E2(75) E1(0) E1(0) —— 第 4 栏位不能退回 15
        assertArrayEquals(
            byteArrayOf(0x5A, 0x5A, 0xA5.toByte(), 0x22, 0x5A, 0x5A), actual,
            "普通房房型 0 必须原样写入 op 83, 旧实现会误退回 15 (0x55)"
        )
    }
}
