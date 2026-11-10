package bnbemu.netty

import bnbemu.packet.EQUIP_SLOTS_PER_GROUP
import bnbemu.handler.ChangeEquipReq
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

    // ========================================================================
    // 水泡皮肤 (category 14) → 尾部 E4 f32 (2026-11-10 定案)
    // 客户端 sub_44A856 把 f32 存进 CSlotAccessoryPacket+32,
    // sub_687B3D 写入 CFxContext+472, 局内 bomber[288] = sub_687DFD(...),
    // 贴图按 "BombDeco/Bomb%03d" 加载; 恒 0 就回落默认水泡 "Bomb/Bomb"。
    // ========================================================================

    /** 复合槽位: 大类 10 (特效/气泡) 子槽 0 = 240, 见 equipGroupOfCategory(14) */
    private fun bombSlot(sub: Int = 0) = 10 * EQUIP_SLOTS_PER_GROUP + sub

    @Test
    fun testRealItemDefForBomb655() {
        // 防止 op 60 定义表漂移: 930000655 必须是 category 14 / param 655
        val def = ItemDef.find(930000655)
        assertEquals(14, def?.category)
        assertEquals(655, def?.param)
        assertEquals("BombDeco/Bomb655", def?.name)
    }

    @Test
    fun testEquippedBombSkinGoesIntoF32() {
        val got = body(
            SetPlayer(slotId = 0, level = 1, equips = mapOf(bombSlot() to 930000655))
        )
        // f32 是定长字段, 装备水泡不改变包长 (旧实现恒 0 时也是 78B)
        assertEquals(78, got.size, "水泡皮肤走 f32, 不应往 count1/2/3 追加条目")

        val acc = decodeAccessories(got)
        assertEquals(655, acc.f32, "f32 必须等于 ItemDef.param (水泡序号)")
        assertEquals(255, acc.f29)
        assertEquals(0, acc.f30)
        assertTrue(acc.decorations.isEmpty())
        assertTrue(acc.effects.isEmpty(), "category 14 不能塞进 count2, 否则局内会挂无效特效")
        assertTrue(acc.avatars.isEmpty())
    }

    @Test
    fun testBombSkinSurvivesGameStartVariant() {
        // 进战斗前的最后一次 op 100 (PracticeStartInfo) 会覆写 CFxContext+472,
        // 这一发必须同样带上水泡, 否则进房时设的值会被清回默认。
        val got = body(
            SetPlayer(slotId = 0, level = 1, isStart = true, equips = mapOf(bombSlot() to 930000655))
        )
        assertEquals(77, got.size)
        assertEquals(655, decodeAccessories(got, isStart = true).f32)
    }

    @Test
    fun testBombSkinCoexistsWithOtherAccessories() {
        // 背景 (count1) + 角色特效 (count2) + 水泡 (f32) 三种通道互不干扰
        val got = body(
            SetPlayer(
                slotId = 0, level = 1,
                equips = mapOf(
                    220 to 710418,   // category 10 背景
                    240 to 2040,     // category 12 特效
                    bombSlot(1) to 930000655, // category 14 水泡
                ),
            )
        )
        val acc = decodeAccessories(got)
        assertEquals(listOf(1 to 418), acc.decorations)
        assertEquals(listOf(Triple(-1, 0, 16)), acc.effects)
        assertEquals(655, acc.f32, "count2 有条目时 f32 仍必须在 f30 之后被正确读到")
        assertEquals(0, acc.f30)
        assertTrue(acc.avatars.isEmpty())
    }

    @Test
    fun testNoEquippedBombSkinKeepsF32Zero() {
        // 未装备 / 卸下 / 未知物品 / 只在背包 (0xFF) 都必须是默认水泡
        val cases = listOf(
            emptyMap(),
            mapOf(bombSlot() to 0),
            mapOf(bombSlot() to 999999999),
            mapOf(0xFF to 930000655),
        )
        cases.forEach { equips ->
            val acc = decodeAccessories(body(SetPlayer(slotId = 0, level = 1, equips = equips)))
            assertEquals(0, acc.f32, "equips=$equips 不应下发水泡皮肤")
        }
    }

    @Test
    fun testSlotAccessoriesOfCategory14UsesParamOnly() {
        val bombDef = { param: Int ->
            ItemDefEntry(itemId = 930000001, category = 14, name = "BombDeco/Bomb001", param = param)
        }
        // param <= 0 客户端 sub_687B3D 会按默认处理, 不应下发
        assertEquals(0, slotAccessoriesOf(mapOf(bombSlot() to 930000001)) { bombDef(0) }.bombSkin)
        assertEquals(0, slotAccessoriesOf(mapOf(bombSlot() to 930000001)) { bombDef(-1) }.bombSkin)

        // 多件时按复合槽位排序取第一件, 结果确定; 且不进 count2
        val defs = mapOf(
            930000041 to ItemDefEntry(itemId = 930000041, category = 14, name = "BombDeco/Bomb041", param = 41),
            930000065 to ItemDefEntry(itemId = 930000065, category = 14, name = "BombDeco/Bomb065", param = 65),
        )
        val acc = slotAccessoriesOf(
            equips = mapOf(bombSlot(2) to 930000065, bombSlot(1) to 930000041),
        ) { defs[it] }
        assertEquals(41, acc.bombSkin, "按复合槽位排序取第一件水泡, 结果必须确定")
        assertTrue(acc.effects.isEmpty())
    }

    // ========================================================================
    // 端到端: C→S op 273 (背包穿上水泡) → user.equips → 开局 op 100 的 f32
    // ========================================================================
    private fun e1(v: Int) = byteArrayOf((v xor 0x5A).toByte())
    private fun e2(v: Int) = byteArrayOf(
        (((v shr 8) and 0xFF) xor 0xA5).toByte(),
        ((v and 0xFF) xor 0x69).toByte(),
    )
    private fun e4(v: Int) = byteArrayOf(
        ((v ushr 24) xor 0x96).toByte(),
        (((v ushr 16) and 0xFF) xor 0xCA).toByte(),
        (((v ushr 8) and 0xFF) xor 0x53).toByte(),
        ((v and 0xFF) xor 0x95).toByte(),
    )

    @Test
    fun testEquipBombViaOp273ReachesStartPacket() {
        // PlayerEquips 是进程级单例, 用独立 id 避免污染其它用例
        val cs = ClientSocket()
        val user = User(cs, 9102, "bomber")
        cs.users += user
        user.equips.clear()

        // action=5 (背包穿脱) / player=0 / count=1 / (复合槽位 240, item 930000655)
        val payload = e1(5) + e1(0) + e2(1) + e1(bombSlot()) + e4(930000655) + e1(1) + e1(1)
        val buf = Unpooled.wrappedBuffer(payload)
        with(ChangeEquipReq) { buf.handle(cs) }
        assertEquals(0, buf.readableBytes(), "op 273 必须被完整消费")
        assertEquals(930000655, user.equips[bombSlot()], "水泡必须记进真实装备槽而不是背包")

        // 开局那一发 op 100 是客户端最后一次重建 CFxContext 的机会
        val got = body(SetPlayer(slotId = 0, user = user, isStart = true))
        assertEquals(655, decodeAccessories(got, isStart = true).f32)

        // 卸下后 f32 归零, 回落默认水泡 (真实客户端脱下上报 slot=0xFF + itemId 进入背包)
        val off = Unpooled.wrappedBuffer(e1(5) + e1(0) + e2(1) + e1(0xFF) + e4(930000655) + e1(1) + e1(1))
        with(ChangeEquipReq) { off.handle(cs) }
        assertEquals(0, decodeAccessories(body(SetPlayer(slotId = 0, user = user, isStart = true)), true).f32)
    }
}
