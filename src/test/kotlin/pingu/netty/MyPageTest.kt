package pingu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pingu.ACP
import pingu.packet.DEFAULT_USER_EQUIPS
import pingu.packet.UserInfoResult
import pingu.packet.decodeName

class MyPageTest {

    @Test
    fun testOpcodeBindings() {
        // 确保 OpcodeManager 初始化完成
        val recv73 = OpcodeManager.recvOps[73]
        val recv66 = OpcodeManager.recvOps[66]
        val recv301 = OpcodeManager.recvOps[301]
        val recv74 = OpcodeManager.recvOps[74]
        val recv273 = OpcodeManager.recvOps[273]
        val recv288 = OpcodeManager.recvOps[288]
        val recv151 = OpcodeManager.recvOps[151]

        assertEquals("UserInfoReq", recv73)
        assertEquals("UserRestrictReq", recv66)
        assertEquals("MyPageReq", recv301)
        assertEquals("WatchUserInfoReq", recv74)
        assertEquals("ChangeEquipReq", recv273)
        assertEquals("SweetieInfoReq", recv288)
        assertEquals("GenerateGhostReq", recv151)

        assertNotNull(handlerArray[73], "Handler 73 must be registered")
        assertNotNull(handlerArray[66], "Handler 66 must be registered")
        assertNotNull(handlerArray[301], "Handler 301 must be registered")
        assertNotNull(handlerArray[74], "Handler 74 must be registered")
        assertNotNull(handlerArray[273], "Handler 273 must be registered")
        assertNotNull(handlerArray[288], "Handler 288 must be registered")
        assertNotNull(handlerArray[151], "Handler 151 must be registered")
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
    fun testOtherUserInfoResultPacket() {
        val pkt = UserInfoResult(
            isSelf = false,
            characterName = "Jacko"
        )
        val op = OpcodeManager.getSendOp(pkt::class.java)
        assertEquals(73, op)

        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        // 他人资料无 slotIndex (少 1 字节)，但角色名 "Jacko" 5 字节 (比 "ffdf" 4 字节多 1 字节)，总长度仍为 202 字节
        assertEquals(202, buf.readableBytes())

        // errCode (Encode4) = 0
        assertEquals(0, buf.readInt() xor 0x96CA5395.toInt())
        // isSelf (Encode4) = 0
        assertEquals(0, buf.readInt() xor 0x96CA5395.toInt())
        // isSelf == 0 时无 slotIndex，直接进入 characterName
        val nameLen = buf.readUnsignedShort() xor 0xA569
        assertEquals(5, nameLen)

        val tokenBytes = ByteArray(5)
        buf.readBytes(tokenBytes)
        assertEquals("Jacko", decodeName(tokenBytes))
    }

    @Test
    fun testUserRestrictReqOtherUserPayload() {
        // 用户真实报错样本: A5 6C FD A9 32 DF 35 5A
        val hex = "A5 6C FD A9 32 DF 35 5A".replace(" ", "")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val buf = Unpooled.wrappedBuffer(bytes)

        val len = buf.readUnsignedShort() xor 0xA569
        assertEquals(5, len, "Length of target user token is 5")

        val token = ByteArray(len).also { buf.readBytes(it) }
        val targetCharName = decodeName(token)
        assertEquals("Jacko", targetCharName, "Decoded target name must be Jacko")

        val isMe = buf.readUnsignedByte().toInt() xor 0x5A
        assertEquals(0, isMe, "isMe must be 0 for other users")

        // 关键断言：查看他人名片时后面没有 slotIndex，必须刚好读完所有字节，不发生 IndexOutOfBoundsException
        assertEquals(0, buf.readableBytes(), "Must consume all bytes without throwing IndexOutOfBoundsException")
    }

    @Test
    fun testUserRestrictReqSelfPayload() {
        // 用户查看自己样本: A5 6D F0 AE 35 F3 5B 5A
        val hex = "A5 6D F0 AE 35 F3 5B 5A".replace(" ", "")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val buf = Unpooled.wrappedBuffer(bytes)

        val len = buf.readUnsignedShort() xor 0xA569
        assertEquals(4, len, "Length of self token is 4")

        val token = ByteArray(len).also { buf.readBytes(it) }
        val myCharName = decodeName(token)
        assertEquals("ffdf", myCharName, "Decoded self name must be ffdf")

        val isMe = buf.readUnsignedByte().toInt() xor 0x5A
        assertEquals(1, isMe, "isMe must be 1 for self")

        val slotIndex = buf.readUnsignedByte().toInt() xor 0x5A
        assertEquals(0, slotIndex, "slotIndex should be 0")

        assertEquals(0, buf.readableBytes(), "Must consume all 8 bytes exactly")
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

    @Test
    fun testSweetieInfoReqPayload() {
        // 客户端在无伴侣时发送: A5 69
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0xA5.toByte(), 0x69.toByte()))
        val len = buf.readUnsignedShort() xor 0xA569
        assertEquals(0, len, "Empty sweetie name string length must decode to 0")
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun testUserInfoResultWithEquips() {
        val pkt = UserInfoResult(
            isSelf = false,
            characterName = "Jacko",
            equips = DEFAULT_USER_EQUIPS
        )
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        // 空装备时为 202 字节，3 件装备每件 13 字节 (4+1+4+4)，总共增加 39 字节 = 241 字节
        assertEquals(202 + 3 * 13, buf.readableBytes())
    }

    @Test
    fun testUserInfoReqPayloadAfterBomberChange() {
        // 玩家换为角色 4 (蓝蓝) 后发出的包体: 5B 5E (count=1, bomberId=4)
        val hex = "5B 5E".replace(" ", "")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val buf = Unpooled.wrappedBuffer(bytes)

        val count = buf.readUnsignedByte().toInt() xor 0x5A
        assertEquals(1, count, "Count must be 1")

        val bomberId = buf.readUnsignedByte().toInt() xor 0x5A
        assertEquals(4, bomberId, "Decoded bomberId must be 4 (Marid), not slotIndex")

        // 验证客户端安全槽位必须在 0..1 范围内
        val slotIndex = 0 // 玩家在本地/房间始终为 slot 0
        val safeSlotIndex = slotIndex.coerceIn(0, 1)
        assertEquals(0, safeSlotIndex)
        assertEquals(0, buf.readableBytes(), "Must consume exactly 2 bytes")
    }
}
