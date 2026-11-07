package bnbemu.netty

import bnbemu.handler.OnConnEstablished
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * C→S op 7 = OnConnEstablished。
 * 客户端连接到 3838 端口后发送的首包，携带用户会话、校验码及频道 Hash。
 */
class OnConnEstablishedTest {

    @Test
    fun testOpcodeBinding() {
        assertEquals("OnConnEstablished", OpcodeManager.recvOps[7])
        assertNotNull(handlerArray[7], "Handler 7 must be registered")
        assertSame(OnConnEstablished, handlerArray[7])
    }

    @Test
    fun testDecodeFullPacket() {
        // enter-channel.pcap stream1 真实 34B 载荷 (去掉 2B opcode 0x0007 / A5 6E):
        // 5B (userCount=1)
        // A5 6D F0 AE 35 F3 (name="ffdf", len=4, token=F0 AE 35 F3)
        // 96 CA 4F BA (userId=639)
        // 96 CA 53 95 (userReserved=0)
        // 00 00 00 00 71 B1 85 89 (8B 校验码)
        // 5A 5B (flag0=0, flag1=1)
        // 84 E9 60 B7 84 E9 60 B7 (双频道 hash 0x12233322)
        // 5B (tailFlag=1)
        val raw = "5B A5 6D F0 AE 35 F3 96 CA 4F BA 96 CA 53 95 00 00 00 00 71 B1 85 89 5A 5B 84 E9 60 B7 84 E9 60 B7 5B"
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()

        assertEquals(34, raw.size, "载荷长度应为 34 字节")

        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val buf = Unpooled.wrappedBuffer(raw)

        with(OnConnEstablished) {
            buf.handle(socket)
        }

        assertEquals(0, buf.readableBytes(), "34B 载荷必须被全部解码消费完毕")
        assertEquals(1, socket.users.size, "应添加 1 位玩家")
        assertEquals(7215, socket.users[0].id, "玩家 userId 应为 7215")
        assertEquals("ffdf", socket.users[0].name, "角色名解密后应为 ffdf")
        assertEquals(7, channel.outboundMessages().size, "进频道应回连发 7 个初始化包")
    }

    @Test
    fun testShortPacketDoesNotThrow() {
        // 短包 (仅有 userCount/name/userId): 容错防崩
        val raw = "5B A5 6D F0 AE 35 F3 96 CA 4F BA"
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val buf = Unpooled.wrappedBuffer(raw)

        with(OnConnEstablished) {
            buf.handle(socket)
        }

        assertEquals(0, buf.readableBytes(), "短包字段应消费完毕")
        assertEquals(1, socket.users.size)
        assertEquals(7215, socket.users[0].id)
        assertEquals("ffdf", socket.users[0].name)
    }
}
