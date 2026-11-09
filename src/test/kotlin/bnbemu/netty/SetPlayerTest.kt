package bnbemu.netty

import bnbemu.packet.EQUIP_SLOTS_PER_GROUP
import bnbemu.packet.ItemDef
import bnbemu.packet.ItemDefEntry
import bnbemu.packet.PracticeRoomInfo
import bnbemu.packet.PracticeStartInfo
import bnbemu.packet.room.SetPlayer
import bnbemu.packet.room.slotAccessoriesOf
import bnbemu.server.User
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SetPlayerTest {

    private fun body(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        val arr = ByteArray(buf.readableBytes())
        buf.readBytes(arr)
        return arr
    }

    private fun decodeLevelAt(bytes: ByteArray, offset: Int = 30): Int {
        // level 是 Encode2: (BE16 ^ 0xa569)
        val high = bytes[offset].toInt() and 0xFF
        val low = bytes[offset + 1].toInt() and 0xFF
        val raw = (high shl 8) or low
        return raw xor 0xa569
    }

    @Test
    fun testOpcodeBinding() {
        assertEquals(100, OpcodeManager.getSendOp(SetPlayer()::class.java), "SetPlayer 必须绑定到 op 100")
    }

    @Test
    fun testRoomLevel1MatchesCaptureBlobExact() {
        // 当 level=1 时, 必须与原始抓包 78B body 逐字节一致
        val captureFile = File("cap/practice_s2c_op100_80B_0.bin")
        assertTrue(captureFile.exists(), "cap/practice_s2c_op100_80B_0.bin 必须存在")
        val exp = captureFile.readBytes().drop(2).toByteArray() // 去掉 2B opcode 前缀

        val pkt = SetPlayer(slotId = 0, level = 1, isStart = false)
        val got = body(pkt)

        assertEquals(78, got.size)
        assertEquals(exp.size, got.size)
        assertEquals(exp.joinToString(" ") { "%02X".format(it) }, got.joinToString(" ") { "%02X".format(it) })
    }

    @Test
    fun testStartLevel1MatchesCaptureBlobExact() {
        // 当 isStart=true, level=1 时, 必须与原始开始抓包 77B body 逐字节一致
        val captureFile = File("cap/practice_s2c_op100_79B_1.bin")
        assertTrue(captureFile.exists(), "cap/practice_s2c_op100_79B_1.bin 必须存在")
        val exp = captureFile.readBytes().drop(2).toByteArray() // 去掉 2B opcode 前缀

        val pkt = SetPlayer(slotId = 0, level = 1, isStart = true)
        val got = body(pkt)

        assertEquals(77, got.size)
        assertEquals(exp.size, got.size)
        assertEquals(exp.joinToString(" ") { "%02X".format(it) }, got.joinToString(" ") { "%02X".format(it) })
    }

    @Test
    fun testDynamicLevel5Encoding() {
        val pkt = SetPlayer(slotId = 0, level = 5, isStart = false)
        val got = body(pkt)
        assertEquals(78, got.size)

        // 偏移 30~31 是 Encode2(level)
        // 5 ^ 0xa569 = 0xa56c -> [0xa5, 0x6c]
        assertEquals(0xa5.toByte(), got[30])
        assertEquals(0x6c.toByte(), got[31])

        // 验证解码等级
        assertEquals(5, decodeLevelAt(got, 30), "解析出的等级必须是 5")
    }

    @Test
    fun testPracticeRoomInfoDelegation() {
        // 验证 PracticeRoomInfo 传 user 时动态应用 level
        val dummySocket = ClientSocket()
        val user = User(c = dummySocket, id = 7215, name = "ffdf", level = 5)

        val pkt = PracticeRoomInfo(user = user)
        val got = body(pkt)
        assertEquals(78, got.size)
        assertEquals(0xa5.toByte(), got[30])
        assertEquals(0x6c.toByte(), got[31])
        assertEquals(5, decodeLevelAt(got, 30))
    }

    // ========================================================================
    // CSlotAccessoryPacket 装扮清单 (2026-11-09): 客户端收到 op 100 会先清空装扮,
    // 再仅凭这三段清单重建, 全 0 = 命令客户端把背景/特效/纸娃娃全部脱掉。
    // ========================================================================

    private data class ParsedAccessories(
        val u16: Int,
        val decorations: List<Pair<Int, Int>>,
        val effects: List<Triple<Int, Int, Int>>,
        val f29: Int,
        val f30: Int,
        val f32: Int,
        val avatars: List<Triple<Int, Int, Int>>,
    )

    /** 按 sub_44A856 的读取顺序解出尾部清单 (前缀 = 固定 67B / 开局版 66B) */
    private fun decodeAccessories(bytes: ByteArray, isStart: Boolean = false): ParsedAccessories {
        val buf = Unpooled.wrappedBuffer(bytes)
        buf.skipBytes(if (isStart) 66 else 67)
        val u16 = buf.Decode2
        val decorations = (0 until buf.Decode1).map { buf.Decode1 to buf.Decode2 }
        val effects = (0 until buf.Decode1).map { Triple(buf.Decode4, buf.Decode4, buf.Decode4) }
        val f29 = buf.Decode1
        val f30 = buf.Decode1
        val f32 = buf.Decode4
        val avatars = (0 until buf.Decode1).map { Triple(buf.Decode4, buf.Decode4, buf.Decode4) }
        assertEquals(0, buf.readableBytes(), "CSlotAccessoryPacket 必须被完整消费")
        return ParsedAccessories(u16, decorations, effects, f29, f30, f32, avatars)
    }

    @Test
    fun testEmptyAccessoriesParseAsCaptureBaseline() {
        val acc = decodeAccessories(body(SetPlayer(slotId = 0, level = 1, isStart = false)))
        assertEquals(0, acc.u16)
        assertTrue(acc.decorations.isEmpty())
        assertTrue(acc.effects.isEmpty())
        assertEquals(255, acc.f29)
        assertEquals(0, acc.f30)
        assertEquals(0, acc.f32)
        assertTrue(acc.avatars.isEmpty())
    }

    @Test
    fun testBackground710418IsSentInCount1() {
        // C→S op 273 实测: slot=0xDC(220) = 大类9(装扮/背景) 子槽4, item=710418
        val got = body(
            SetPlayer(
                slotId = 0, level = 1, isStart = false,
                equips = mapOf(9 * EQUIP_SLOTS_PER_GROUP + 4 to 710418),
            )
        )
        assertEquals(81, got.size, "78B 基准 + 1 条 count1 (E1+E2=3B)")

        // 固定前缀仍与真实抓包逐字节一致, 只在尾部追加装扮清单
        val exp = File("cap/practice_s2c_op100_80B_0.bin").readBytes().drop(2).toByteArray()
        assertEquals(
            exp.take(67).joinToString(" ") { "%02X".format(it) },
            got.take(67).joinToString(" ") { "%02X".format(it) },
        )

        // ItemDef 710418 = category 10 / skinId 418 (背景序号) / targetCharId 1
        val acc = decodeAccessories(got)
        assertEquals(listOf(1 to 418), acc.decorations)
        assertTrue(acc.effects.isEmpty())
        assertEquals(255, acc.f29)
        assertEquals(0, acc.f32)
        assertTrue(acc.avatars.isEmpty())
    }

    @Test
    fun testStartVariantAlsoCarriesDecoration() {
        val got = body(
            SetPlayer(slotId = 0, level = 1, isStart = true, equips = mapOf(220 to 710418))
        )
        assertEquals(80, got.size, "77B 基准 + 3B")
        assertEquals(listOf(1 to 418), decodeAccessories(got, isStart = true).decorations)
    }

    @Test
    fun testUnequipAndUnknownItemProduceNoDecoration() {
        val got = body(
            SetPlayer(slotId = 0, level = 1, equips = mapOf(220 to 0, 221 to 999999999))
        )
        assertEquals(78, got.size)
        assertTrue(decodeAccessories(got).decorations.isEmpty())
    }

    @Test
    fun testCount2OrderBeforeTailFlags() {
        // 2040 "CharDeco A0016": category 12 / skinId -1 / targetCharId 0 / param 16
        val got = body(SetPlayer(slotId = 0, level = 1, equips = mapOf(240 to 2040)))
        assertEquals(90, got.size, "78B 基准 + 1 条 count2 (3×E4=12B)")
        val acc = decodeAccessories(got)
        assertEquals(listOf(Triple(-1, 0, 16)), acc.effects)
        assertTrue(acc.decorations.isEmpty())
        assertEquals(255, acc.f29, "count2 条目必须写在 f29/f30/f32 之前, 否则这里会错位")
        assertEquals(0, acc.f30)
        assertEquals(0, acc.f32)
        assertTrue(acc.avatars.isEmpty())
    }

    @Test
    fun testSlotAccessoriesOfMapsAllThreeCategories() {
        // category 19 纸娃娃装扮不在基础定义表里, 用注入的定义验证 count3 映射
        val avatarDef = ItemDefEntry(
            itemId = 990019, category = 19, name = "Item/Avatar",
            skinId = 7, targetCharId = 3, param = 5,
        )
        val acc = slotAccessoriesOf(
            equips = mapOf(220 to 710418, 240 to 2040, 5 * EQUIP_SLOTS_PER_GROUP + 3 to 990019),
            findItem = { id -> if (id == 990019) avatarDef else ItemDef.find(id) },
        )
        assertEquals(listOf(1 to 418), acc.decorations)
        assertEquals(listOf(Triple(-1, 0, 16)), acc.effects)
        assertEquals(listOf(Triple(7, 3, 5)), acc.avatars)

        // param <= 0 时客户端 CGOAvatar::sub_458CD3 会整条丢弃, 不应下发
        val rejected = slotAccessoriesOf(
            equips = mapOf(240 to 990019),
            findItem = { avatarDef.copy(param = 0) },
        )
        assertTrue(rejected.avatars.isEmpty())
    }
}
