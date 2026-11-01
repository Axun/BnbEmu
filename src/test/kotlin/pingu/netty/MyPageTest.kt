package pingu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pingu.ACP
import pingu.packet.UserInfoResult

class MyPageTest {

    @Test
    fun testOpcodeBindings() {
        // 确保 OpcodeManager 初始化完成
        val recv73 = OpcodeManager.recvOps[73]
        val recv66 = OpcodeManager.recvOps[66]
        val recv301 = OpcodeManager.recvOps[301]
        val recv74 = OpcodeManager.recvOps[74]
        val recv273 = OpcodeManager.recvOps[273]

        assertEquals("UserInfoReq", recv73)
        assertTrue(recv66 == "UserRestrictReq" || recv66 == "ModifyUserInfoReq")
        assertEquals("MyPageReq", recv301)
        assertEquals("WatchUserInfoReq", recv74)
        assertEquals("ChangeEquipReq", recv273)

        assertNotNull(handlerArray[73], "Handler 73 must be registered")
        assertNotNull(handlerArray[66], "Handler 66 must be registered")
        assertNotNull(handlerArray[301], "Handler 301 must be registered")
        assertNotNull(handlerArray[74], "Handler 74 must be registered")
        assertNotNull(handlerArray[273], "Handler 273 must be registered")
    }

    @Test
    fun testUserInfoResultPacket() {
        val pkt = UserInfoResult()
        val op = OpcodeManager.getSendOp(pkt::class.java)
        assertEquals(73, op, "UserInfoResult send opcode must be 73")

        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        // 默认参数下的载荷大小为 202 字节 (包含 favorite="Bazzi", sweetieName="")
        assertEquals(202, buf.readableBytes(), "UserInfoResult payload length should be 202 bytes")

        // 验证结构体前部解析:
        // errCode (Encode4) = 0
        assertEquals(0, buf.readInt() xor 0x96CA5395.toInt())
        // isSelf (Encode4) = 1
        assertEquals(1, buf.readInt() xor 0x96CA5395.toInt())
        // slotIndex (Encode1) = 0
        assertEquals(0, buf.readUnsignedByte().toInt() xor 0x5A)
        // characterName: len (Encode2) = 4
        val nameLen = buf.readUnsignedShort() xor 0xA569
        assertEquals(4, nameLen)
        // 读取 4 字节 token 并按客户端 0x75daf0 逻辑解密
        val tokenBytes = ByteArray(4)
        buf.readBytes(tokenBytes)
        // 客户端 0x75da30: BE int xor 0x96CA5395, 按 LE 写入内存
        val be = ((tokenBytes[0].toInt() and 0xFF) shl 24) or
                ((tokenBytes[1].toInt() and 0xFF) shl 16) or
                ((tokenBytes[2].toInt() and 0xFF) shl 8) or
                (tokenBytes[3].toInt() and 0xFF)
        val le = be xor 0x96CA5395.toInt()
        val decryptedName = String(
            byteArrayOf(
                (le and 0xFF).toByte(),
                ((le ushr 8) and 0xFF).toByte(),
                ((le ushr 16) and 0xFF).toByte(),
                ((le ushr 24) and 0xFF).toByte()
            ), ACP
        )
        assertEquals("ffdf", decryptedName, "Decrypted characterName must match ffdf!")
    }

    @Test
    fun testChangeEquipReqPayload() {
        // 日志样本: 59 5A A5 68 CE 96 CA 5B B7 5B 5A
        val hex = "59 5A A5 68 CE 96 CA 5B B7 5B 5A".replace(" ", "")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val buf = Unpooled.wrappedBuffer(bytes)

        // 模拟 PKT.kt 里的 Decode1, Decode2, Decode4
        val action = buf.readUnsignedByte().toInt() xor 0x5A
        val slotIndex = buf.readUnsignedByte().toInt() xor 0x5A
        val count = buf.readUnsignedShort() xor 0xA569

        assertEquals(3, action, "Action 3 is MyPage dialog exit")
        assertEquals(0, slotIndex)
        assertEquals(1, count, "Modified item count is 1")

        val equipSlot = buf.readUnsignedByte().toInt() xor 0x5A
        val itemId = buf.readInt() xor 0x96CA5395.toInt()

        assertEquals(0x94, equipSlot)
        assertEquals(2082, itemId, "Equipped item ID should be 2082")

        val sessionCount = buf.readUnsignedByte().toInt() xor 0x5A
        val flag = buf.readUnsignedByte().toInt() xor 0x5A

        assertEquals(1, sessionCount)
        assertEquals(0, flag)
        assertEquals(0, buf.readableBytes(), "Must consume all 11 bytes exactly")
    }
}
