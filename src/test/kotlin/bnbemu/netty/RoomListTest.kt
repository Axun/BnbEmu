package bnbemu.netty

import bnbemu.ACP
import bnbemu.packet.lobby.RoomEntry
import bnbemu.packet.lobby.RoomList
import bnbemu.packet.lobby.SESSION_TYPE_NORMAL
import bnbemu.packet.lobby.SESSION_TYPE_PRACTICE
import bnbemu.domain.PlayerProfile
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.FLAG_CLOSED
import bnbemu.server.Room
import bnbemu.server.sendRoomEntryChain
import bnbemu.service.PlayerService
import bnbemu.server.state
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.Inflater

/**
 * S→C op 87 RoomList（CLobbyStage::OnPacket case 0x57 → sub_50FF27 + sub_504E9B）回归测试。
 */
class RoomListTest {

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    @Test
    fun `op 87 绑定到 RoomList`() {
        assertEquals(87, OpcodeManager.getSendOp(RoomList()::class.java))
    }

    @Test
    fun `空列表只发 E2(0) 不带 SSD 块`() {
        val body = encode(RoomList(emptyList()))
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x69), body, "空列表仅 E2(0)")
        assertEquals(emptyList<RoomEntry>(), decode(body))
    }

    @Test
    fun `默认 rd 样本与 chan_s2c_op87_41B_0 逐字节一致`() {
        val cap = File("cap/chan_s2c_op87_41B_0.bin").takeIf { it.isFile } ?: return
        val expectedBody = cap.readBytes().drop(2).toByteArray()
        assertArrayEquals(expectedBody, encode(RoomList()))
    }

    @Test
    fun `全部 5 组官方抓包样本重建逐字节一致`() {
        data class Case(val file: String, val entry: RoomEntry)
        val cases = listOf(
            Case(
                "cap/chan_s2c_op87_41B_0.bin",
                RoomEntry(
                    roomId = 61, handle = 119, name = "rd",
                    hasPassword = false, unk54 = 0x90, isPlaying = false, unk78 = 0,
                    curPlusClosed = 8, closedSlots = 7, maxPlusClosed = 8,
                    sessionType = 16, mapId = 4, unkA8 = 0, feature67 = 0,
                ),
            ),
            Case(
                "cap/practice_s2c_op87_39B_6.bin",
                RoomEntry(
                    roomId = 75, handle = 7215, name = "",
                    hasPassword = false, unk54 = 0x00, isPlaying = false, unk78 = 0,
                    curPlusClosed = 1, closedSlots = 0, maxPlusClosed = 8,
                    sessionType = SESSION_TYPE_PRACTICE, mapId = 172, unkA8 = 0, feature67 = 0,
                ),
            ),
            Case(
                "cap/practice_s2c_op87_40B_1.bin",
                RoomEntry(
                    roomId = 78, handle = 7465, name = "a",
                    hasPassword = false, unk54 = 0xA6, isPlaying = false, unk78 = 0,
                    curPlusClosed = 8, closedSlots = 7, maxPlusClosed = 8,
                    sessionType = 16, mapId = 6, unkA8 = 0, feature67 = 0,
                ),
            ),
            Case(
                "cap/practice_s2c_op87_47B_0.bin",
                RoomEntry(
                    roomId = 71, handle = 3671, name = "K8-kUn--",
                    hasPassword = true, unk54 = 0x16, isPlaying = false, unk78 = 0,
                    curPlusClosed = 2, closedSlots = 0, maxPlusClosed = 8,
                    sessionType = SESSION_TYPE_NORMAL, mapId = 1, unkA8 = 0, feature67 = 0,
                ),
            ),
            Case(
                "cap/practice_s2c_op87_56B_2.bin",
                RoomEntry(
                    roomId = 92, handle = 5765, name = "Everyone welcome!",
                    hasPassword = false, unk54 = 0x8C, isPlaying = true, unk78 = 0,
                    curPlusClosed = 5, closedSlots = 4, maxPlusClosed = 8,
                    sessionType = 16, mapId = 159, unkA8 = 0, feature67 = 0,
                ),
            ),
        )
        for (c in cases) {
            val f = File(c.file).takeIf { it.isFile } ?: continue
            val expectedBody = f.readBytes().drop(2).toByteArray()
            val actual = encode(RoomList(listOf(c.entry)))
            assertArrayEquals(expectedBody, actual, "样本 ${c.file} 重编码必须逐字节一致")
            assertEquals(listOf(c.entry), decode(actual), "样本 ${c.file} 解码往返一致")
        }
    }

    @Test
    fun `非 4B 对齐房名与多房间共用单个 SSD 块可逆解析`() {
        val rooms = listOf(
            RoomEntry(
                roomId = 1, handle = 1001, name = "abc",
                hasPassword = false, unk54 = 0x10, isPlaying = false, unk78 = 0,
                curPlusClosed = 1, closedSlots = 0, maxPlusClosed = 8,
                sessionType = SESSION_TYPE_NORMAL, mapId = 12,
            ),
            RoomEntry(
                roomId = 75, handle = 7215, name = "Come quick~",
                hasPassword = true, unk54 = 0x90, isPlaying = true, unk78 = 2,
                curPlusClosed = 4, closedSlots = 2, maxPlusClosed = 8,
                sessionType = SESSION_TYPE_PRACTICE, mapId = 253,
            ),
            RoomEntry(
                roomId = 120, handle = 9999, name = "x",
                hasPassword = false, unk54 = 0, isPlaying = false, unk78 = 0,
                curPlusClosed = 8, closedSlots = 0, maxPlusClosed = 8,
                sessionType = 14, mapId = 99,
            ),
        )
        val body = encode(RoomList(rooms, compress = false))
        assertEquals(rooms, decode(body))
    }

    @Test
    fun `多房间走 zlib 压缩分支时仍为单个 SSD 块且可正确解压`() {
        val rooms = (1..20).map { i ->
            RoomEntry(
                roomId = i,
                handle = 5000 + i,
                name = "Practice Room #$i",
                hasPassword = i % 2 == 0,
                unk54 = 0x90,
                isPlaying = i % 3 == 0,
                curPlusClosed = 3,
                closedSlots = 1,
                maxPlusClosed = 8,
                sessionType = SESSION_TYPE_NORMAL,
                mapId = 100 + i,
            )
        }
        val body = encode(RoomList(rooms, compress = true))
        assertEquals(rooms, decode(body, expectCompressed = true))
    }

    @Test
    fun `sendRoomEntryChain 下发的 op 87 与 Room 元数据及封闭槽位一致`() {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val user = PlayerService.registerProfile(PlayerProfile(7215, "kcworms1", "ffdf", bomber = 0))
        cs.playerId = user.userId

        val savedName = Room.name
        val savedPw = Room.password
        val savedType = Room.sessionType
        val savedMapId = Room.mapId
        val savedSlots = Room.slots.map { listOf(it.playerId, it.client, it.isAI, it.state) }
        try {
            Room.name = "MyTestRoom!"
            Room.password = "secret"
            Room.sessionType = SESSION_TYPE_NORMAL
            Room.mapId = 176
            Room.slots[0].apply { assignHuman(user.userId, cs); isAI = false; state = FLAG_CHIEF }
            for (i in 1..5) Room.slots[i].apply { clearHuman(); isAI = true; state = 0 }
            for (i in 6..7) Room.slots[i].apply { clearHuman(); isAI = false; state = FLAG_CLOSED }

            sendRoomEntryChain(cs, init = false)
            val pkts = generateSequence { ch.readOutbound<PKT>() }.toList()
            val roomListPkt = pkts.first { OpcodeManager.getSendOp(it::class.java) == 87 }
            val parsed = decode(encode(roomListPkt)).single()

            assertEquals(Room.sessionId, parsed.roomId, "必须与 op 83 的 sessionId 一致以命中客户端房间缓存")
            assertEquals(7215, parsed.handle)
            assertEquals("MyTestRoom!", parsed.name)
            assertEquals(true, parsed.hasPassword)
            assertEquals(SESSION_TYPE_NORMAL, parsed.sessionType)
            assertEquals(176, parsed.mapId)
            assertEquals(2, parsed.closedSlots)
            assertEquals(3, parsed.curPlusClosed, "1 真人 + 2 封闭槽 = 3，显示 1/6")
            assertEquals(8, parsed.maxPlusClosed)
        } finally {
            Room.name = savedName
            Room.password = savedPw
            Room.sessionType = savedType
            Room.mapId = savedMapId
            Room.slots.forEachIndexed { idx, slot ->
                slot.assignHuman(savedSlots[idx][0] as Int?, savedSlots[idx][1] as ClientSocket?)
                slot.isAI = savedSlots[idx][2] as Boolean
                slot.state = savedSlots[idx][3] as Byte
            }
            ch.finishAndReleaseAll()
        }
    }

    // ---- 独立解码器（镜像 CA.exe sub_50FF27 → sub_78E2F0 → sub_78C120 → sub_78BB60 → sub_504E9B）----

    private fun decode(body: ByteArray, expectCompressed: Boolean? = null): List<RoomEntry> {
        var p = 0
        val count = (((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)) xor 0xA569
        p += 2
        if (count == 0) {
            assertEquals(2, body.size, "count == 0 时不得再有后续字节")
            return emptyList()
        }
        val encLen = intBe(body, p) xor 0x96CA5395.toInt()
        p += 4
        assertEquals(body.size, p + encLen, "encLen 必须正好覆盖剩余包体")

        val block = unSsdEncode(body.copyOfRange(p, p + encLen))
        assertEquals(0x18.toByte(), block[0], "SSD magic 0x18")
        val payloadLen = le32(block, 1)
        assertEquals(block.size, payloadLen + 5, "SSD block len == payloadLen + 5")

        val compFlag = block[5].toInt() and 0xFF
        if (expectCompressed != null) {
            assertEquals(if (expectCompressed) 1 else 0, compFlag)
        }
        val stream = when (compFlag) {
            0 -> block.copyOfRange(6, block.size)
            1 -> {
                val rawLen = le32(block, 6)
                val inflater = Inflater()
                try {
                    inflater.setInput(block, 10, block.size - 10)
                    val out = ByteArray(rawLen)
                    val actualLen = inflater.inflate(out)
                    assertEquals(rawLen, actualLen)
                    out
                } finally {
                    inflater.end()
                }
            }
            else -> error("未知 SSD 压缩标志: $compFlag")
        }

        var q = 0
        val entries = (0 until count).map {
            val roomId = le16(stream, q); q += 2
            val handle = le32(stream, q); q += 4
            val nameLen = le32(stream, q); q += 4
            val name = String(stream, q, nameLen, ACP); q += nameLen
            val hasPassword = (stream[q++].toInt() and 0xFF) != 0
            val unk54 = stream[q++].toInt() and 0xFF
            val isPlaying = (stream[q++].toInt() and 0xFF) != 0
            val unk78 = le16(stream, q); q += 2
            val curPlusClosed = stream[q++].toInt() and 0xFF
            val closedSlots = stream[q++].toInt() and 0xFF
            val maxPlusClosed = stream[q++].toInt() and 0xFF
            val sessionType = stream[q++].toInt() and 0xFF
            val mapId = le32(stream, q); q += 4
            val unkA8 = stream[q++].toInt() and 0xFF
            val feature67 = stream[q++].toInt() and 0xFF
            RoomEntry(
                roomId = roomId,
                handle = handle,
                name = name,
                hasPassword = hasPassword,
                unk54 = unk54,
                isPlaying = isPlaying,
                unk78 = unk78,
                curPlusClosed = curPlusClosed,
                closedSlots = closedSlots,
                maxPlusClosed = maxPlusClosed,
                sessionType = sessionType,
                mapId = mapId,
                unkA8 = unkA8,
                feature67 = feature67,
            )
        }
        assertEquals(stream.size, q, "roomsStream 必须被全部消费完毕")
        return entries
    }

    private fun unSsdEncode(raw: ByteArray): ByteArray {
        val out = ByteArray(raw.size)
        var i = 0
        while (i + 4 <= raw.size) {
            val v = intBe(raw, i) xor 0x96CA5395.toInt()
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i + 3] = ((v ushr 24) and 0xFF).toByte()
            i += 4
        }
        if (i + 2 <= raw.size) {
            val v = (((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)) xor 0xA569
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v ushr 8) and 0xFF).toByte()
            i += 2
        }
        if (i < raw.size) out[i] = ((raw[i].toInt() and 0xFF) xor 0x5A).toByte()
        return out
    }

    private fun intBe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        le16(b, off) or (le16(b, off + 2) shl 16)
}
