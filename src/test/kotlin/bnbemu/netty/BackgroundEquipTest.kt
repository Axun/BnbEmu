package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.handler.ChangeEquipReq
import bnbemu.handler.JoinSessionReq
import bnbemu.packet.EQUIP_SLOTS_PER_GROUP
import bnbemu.server.Room
import bnbemu.server.User
import bnbemu.server.state

/**
 * 「商城装扮页设了背景，回大厅后被自动取消装备」的回归测试。
 *
 * 根因两条 (见 ChangeEquipReq / UserInventory 注释):
 *  1. C→S op 273 的 E1(equipSlot) 是**复合槽位** = 24×装备大类 + 子槽位;
 *     装扮背景 category 10 → 大类 9 ⇒ 槽位 216~239。旧实现用 `MAX_EQUIP_SLOT = 200`
 *     把 216+ 全判成背包物品, 背景因此永远进不了 `user.equips`。
 *  2. S→C op 198 是客户端的「全量真相」(`sub_67601D` 先清空背包+装备再重建),
 *     而旧实现只下发「默认三件 + 背包物品」, 于是每次回大厅重走进频道握手 (op 81)
 *     收到 198 后, 客户端就把背景槽清空了。
 *
 * 这里锁住: 273(背景) → equips 记录 → 198 以真实槽位回放; 以及卸下后退回背包。
 */
class BackgroundEquipTest {

    /**
     * ⚠️ 背包/装备仓库是进程级单例 (PlayerBag/PlayerEquips 按 userId 归档), 且 Maven 在同一 JVM
     * 里跑完所有测试, 所以这里必须用独立 id, 否则会污染 ShopTest 等用例 (它们用 id=0)。
     */
    private val USER_ID = 9101

    /** 装扮背景 710418 = 商城 "Slot background 418" (category 10 / Item/SlotDeco) */
    private val backgroundItemId = 710418

    /**
     * 复合槽位: 大类 9 (装扮/背景) 的子槽 4 → 9*24+4 = 220 (0xDC), 实测 C→S op 273 上报值。
     * 子槽由 `sub_6580F2(category=10, targetCharId)` = targetCharId + 3 算出;
     * 710418 的 targetCharId = 1 (背景) ⇒ 1 + 3 = 4。
     */
    private val backgroundSlot = 9 * EQUIP_SLOTS_PER_GROUP + 4

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

    /** 组一个 C→S op 273 的明文→混淆载荷 (handler 用 Decode1/2/4 解) */
    private fun changeEquipPayload(
        action: Int,
        entries: List<Pair<Int, Int>>,
        tail: Boolean = true,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out += e1(action).toList()
        out += e1(0).toList() // playerIndex
        out += e2(entries.size).toList()
        entries.forEach { (slot, itemId) ->
            out += e1(slot).toList()
            out += e4(itemId).toList()
        }
        if (tail) {
            out += e1(1).toList() // 在线玩家数
            out += e1(1).toList()
        }
        return out.toByteArray()
    }

    private fun sendChangeEquip(cs: ClientSocket, payload: ByteArray) {
        val buf = Unpooled.wrappedBuffer(payload)
        with(ChangeEquipReq) { buf.handle(cs) }
        assertEquals(0, buf.readableBytes(), "op 273 必须被完整消费")
    }

    /** 抽 op 198 里的 (itemId, slot) 列表 */
    private fun decodeOp198(pkt: PKT): List<Pair<Int, Int>> {
        val out = Unpooled.buffer()
        pkt.encode(SendPacketBase(out, 3))
        val invCount = out.Decode1
        assertEquals(2, invCount, "op 198 两位玩家各一份清单")
        val result = ArrayList<Pair<Int, Int>>()
        repeat(invCount) { idx ->
            val count = out.Decode4
            repeat(count) {
                val itemId = out.Decode4
                val slot = out.Decode1
                out.Decode4                     // param
                val tokLen = out.Decode2
                out.skipBytes(tokLen)           // token
                out.skipBytes(8)                // expireDate
                out.Decode4                     // duration
                out.Decode1                     // status
                if (idx == 0) result += itemId to slot
            }
        }
        assertEquals(0, out.readableBytes(), "op 198 包体应被完整消费")
        return result
    }

    @Test
    fun testBackgroundEquipKeptInEquipsNotBag() {
        val cs = ClientSocket()
        val user = User(cs, USER_ID, "tester")
        cs.users += user

        sendChangeEquip(cs, changeEquipPayload(1, listOf(backgroundSlot to backgroundItemId)))

        assertEquals(
            backgroundItemId, user.equips[backgroundSlot],
            "装扮背景 (槽位 0xDC/220) 必须记进装备槽, 不能被 MAX_EQUIP_SLOT 判成背包物品"
        )
        assertTrue(
            user.items.none { it.itemId == backgroundItemId },
            "穿上后不应再残留在背包列表里 (否则 op 198 会下发两条重复条目)"
        )
    }

    @Test
    fun testBackgroundSurvivesOp198Rebuild() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = User(cs, id = USER_ID + 1, name = "ffdf")
        cs.users += user
        ch.pipeline().addLast(cs)

        try {
            sendChangeEquip(cs, changeEquipPayload(1, listOf(backgroundSlot to backgroundItemId)))

            // 客户端回大厅后重走进频道握手 (op 81)
            val buf = Unpooled.buffer()
            with(JoinSessionReq) { buf.handle(cs) }

            val packets = mutableListOf<PKT>()
            while (true) packets += ch.readOutbound<PKT>() ?: break
            val op198 = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 198 }
            assertNotNull(op198, "JoinSessionReq 必须下发 op 198")

            val entries = decodeOp198(op198!!)
            val bg = entries.firstOrNull { it.first == backgroundItemId }
            assertNotNull(bg, "op 198 必须回放已装备的背景 (否则客户端清空重建后背景被取消装备)")
            assertEquals(
                4, bg!!.second,
                "op 198 的 slot 只放子槽位 (复合槽位 220 % 24 = 4), 客户端按 category 还原大类"
            )
            assertTrue(
                entries.count { it.first == backgroundItemId } == 1,
                "背景只能出现一次 (不能既装备又在背包里)"
            )
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    /**
     * 真实客户端 CA.exe 的卸下行为回归:
     * sub_659004 在装备槽 item=0 时直接跳过, 仅在背包向量遍历时上报 (slot=0xFF, itemId=backgroundItemId)。
     * 服务端据此从 user.equips 中卸下该物品并退回背包。
     */
    @Test
    fun testRealClientUnequipReports0xFF() {
        val cs = ClientSocket()
        val user = User(cs, USER_ID + 10, "real_unequip_tester")
        cs.users += user

        // 先穿上
        sendChangeEquip(cs, changeEquipPayload(1, listOf(backgroundSlot to backgroundItemId)))
        assertEquals(backgroundItemId, user.equips[backgroundSlot])

        // 真实客户端卸下: 上报 slot=0xFF + itemId
        sendChangeEquip(cs, changeEquipPayload(5, listOf(0xFF to backgroundItemId)))

        assertNull(user.equips[backgroundSlot], "真实客户端发 0xFF 卸下后装备槽必须被清空")
        assertTrue(
            user.items.any { it.itemId == backgroundItemId },
            "卸下的物品必须退回背包"
        )
    }

    /**
     * 房间阶段换装/卸下外观刷新测试:
     * 当玩家位于房间槽位时，任何装备变更 (穿上/卸下) 均需向房间广播 S→C op 100 SetPlayer,
     * 触发客户端刷新槽位 UI (CSlotView) 并重绘/清理装扮。
     */
    @Test
    fun testRoomSlotBroadcastsSetPlayerOnEquipAndUnequip() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = User(cs, USER_ID + 20, "room_equip_tester")
        cs.users += user
        ch.pipeline().addLast(cs)

        val previousUser = Room.slots[0].user
        try {
            Room.slots[0].user = user
            Room.slots[0].state = 0x01 // FLAG_CHIEF

            // 1. 在房间内穿上背景
            sendChangeEquip(cs, changeEquipPayload(5, listOf(backgroundSlot to backgroundItemId)))
            val pkt1 = ch.readOutbound<PKT>()
            assertNotNull(pkt1, "房间内穿上装备必须广播 SetPlayer (op 100)")
            assertEquals(100, OpcodeManager.getSendOp(pkt1!!::class.java), "广播包 opcode 必须为 100")

            // 2. 在房间内脱掉背景 (真实客户端发 0xFF + itemId)
            sendChangeEquip(cs, changeEquipPayload(5, listOf(0xFF to backgroundItemId)))
            val pkt2 = ch.readOutbound<PKT>()
            assertNotNull(pkt2, "房间内脱掉装备必须广播 SetPlayer (op 100)")
            assertEquals(100, OpcodeManager.getSendOp(pkt2!!::class.java), "广播包 opcode 必须为 100")
            assertNull(user.equips[backgroundSlot], "装备槽中的背景必须已被脱下")
        } finally {
            Room.slots[0].user = previousUser
            ch.finishAndReleaseAll()
        }
    }
}
