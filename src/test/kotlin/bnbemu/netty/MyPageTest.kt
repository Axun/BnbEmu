package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.ACP
import bnbemu.CaptureFixtures
import bnbemu.packet.user.UserInfoResult
import bnbemu.codec.decodeName

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
        val pkt = UserInfoResult(characterName = CaptureFixtures.CAPTURE_CHAR_NAME)
        val op = OpcodeManager.getSendOp(pkt::class.java)
        assertEquals(73, op, "UserInfoResult send opcode must be 73")

        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf)
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
        val sendPkt = SendPacketBase(buf)
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
            equips = CaptureFixtures.DEFAULT_USER_EQUIPS
        )
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf)
        pkt.encode(sendPkt)

        // 空装备时为 202 字节，每件装备 13 字节 (4+1+4+4)
        assertEquals(202 + CaptureFixtures.DEFAULT_USER_EQUIPS.size * 13, buf.readableBytes())
    }

    @Test
    fun testUserInfoResultCarriesExpPercentAt0x94() {
        // 个人主页经验条（CA.exe 0x005DD121: `60 * *(dword*)&dialog[108212] / 999`）读的是
        // PUserInfoResult +0x94，不是 +0x8c/+0x90 的文本数值。这里锁死字段位置，防止再退回 0。
        val pkt = UserInfoResult(
            characterName = "ffdf",
            win = 3,
            draw = 0,
            lose = 12,
            curExp = 420,
            maxExp = 1000,
            expPercent = 420,
            level = 5,
        )
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))

        assertEquals(0, readE4(buf)) // errCode
        assertEquals(1, readE4(buf)) // isSelf
        assertEquals(0, buf.readUnsignedByte().toInt() xor 0x5A) // slotIndex
        assertEquals("ffdf", readToken(buf)) // Nickname +0x44
        assertEquals(0, buf.readUnsignedByte().toInt() xor 0x5A) // Gender +0x74
        assertEquals("PopTag Forever!", readToken(buf)) // Profile +0x180
        assertEquals("", readToken(buf)) // Guild +0x168
        assertEquals(0, buf.readUnsignedByte().toInt() xor 0x5A) // GuildRank +0x164
        assertEquals(3, readE4(buf)) // Wins +0x78
        assertEquals(0, readE4(buf)) // Draws +0x7c
        assertEquals(12, readE4(buf)) // Losses +0x80
        assertEquals(420, readE4(buf)) // curExp +0x8c
        assertEquals(1000, readE4(buf)) // maxExp +0x90
        assertEquals(420, readE4(buf), "+0x94 经验千分比必须随 op 73 下发，否则个人主页经验条恒为 0")
    }

    @Test
    fun testUserInfoResultCarriesRecentAndRankedRecord() {
        // 个人主页 "Win Rate" 行不是用 +0x78/+0x80 算的：feature 47 下客户端用 +0xa0..0xa2
        // （最近战绩 胜/负/平）算 RecentWinRate；排位一路用 +0xac..0xb4。两处都要下发。
        val pkt = UserInfoResult(
            characterName = "ffdf",
            win = 3,
            draw = 0,
            lose = 12,
            recentWin = 3,
            recentLose = 12,
            recentDraw = 0,
            rankedWin = 3,
            rankedDraw = 0,
            rankedLose = 12,
        )
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))

        // 跳到 +0xa0 之前的固定字段
        readE4(buf) // errCode
        readE4(buf) // isSelf
        buf.readUnsignedByte() // slotIndex
        readToken(buf) // nickname
        buf.readUnsignedByte() // gender
        readToken(buf) // profile
        readToken(buf) // guild
        buf.readUnsignedByte() // guildRank
        readE4(buf); readE4(buf); readE4(buf) // win/draw/lose
        readE4(buf); readE4(buf); readE4(buf) // curExp/maxExp/expPercent
        buf.readUnsignedByte() // +0x125
        readE4(buf) // +0x128
        readToken(buf) // +0x12c
        buf.readUnsignedShort() // +0x144
        readToken(buf) // +0x148
        readE4(buf) // +0x160

        assertEquals(3, buf.readUnsignedByte().toInt() xor 0x5A, "+0xa0 最近战绩-胜")
        assertEquals(12, buf.readUnsignedByte().toInt() xor 0x5A, "+0xa1 最近战绩-负")
        assertEquals(0, buf.readUnsignedByte().toInt() xor 0x5A, "+0xa2 最近战绩-平")
        readE4(buf) // +0xa8
        buf.readUnsignedByte() // +0xa4
        assertEquals(3, readE4(buf), "+0xac 排位战绩-胜")
        assertEquals(0, readE4(buf), "+0xb0 排位战绩-平")
        assertEquals(12, readE4(buf), "+0xb4 排位战绩-负")
    }

    private fun readE4(buf: io.netty.buffer.ByteBuf): Int = buf.readInt() xor 0x96CA5395.toInt()

    private fun readToken(buf: io.netty.buffer.ByteBuf): String {
        val len = buf.readUnsignedShort() xor 0xA569
        if (len == 0) return ""
        val token = ByteArray(len).also { buf.readBytes(it) }
        return decodeName(token)
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
