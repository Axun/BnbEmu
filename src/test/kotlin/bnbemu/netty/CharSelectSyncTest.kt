package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.handler.JoinSessionReq
import bnbemu.handler.UserInfoReq
import bnbemu.handler.sendUserInfo
import bnbemu.packet.DEFAULT_BAG_LEVEL
import bnbemu.packet.ItemDef
import bnbemu.packet.UserDynamicInfo
import bnbemu.packet.UserDynamicInfoValue
import bnbemu.server.EquipSlot
import bnbemu.server.User
import java.io.File

/**
 * 「商城里换角色 → 返回大厅后角色变回皮蛋」的回归测试。
 *
 * 两个根因:
 *  1. C→S op 73 是**商城/大厅换角色的唯一上报通道** (商城的选角界面不发 op 273 装备包),
 *     旧 handler 只写 `Room.slots.find { it.user?.c == c }` —— 大厅里玩家不在房间槽位,
 *     `user.bomber` 永远停在旧值 ⇒ 下一次 op 198 的默认角色卡还是皮蛋。
 *     (反过来在大厅换角色走 op 87 SelectBomberReq, 那条路会写 user.bomber, 所以正常)
 *  2. op 60 的物品定义表只有 itemId=1 (Dao), 角色卡 2..7 缺失 ⇒ 客户端
 *     `ApplyEquippedInventory` (`0x6757A6`) 查不到定义会整条丢弃, 角色槽清空。
 *  3. 名片 (op 73 UserInfoResult) 曾把 5/4/10/2 之外的角色一律映射成 Dao。
 */
class CharSelectSyncTest {

    /** 进程级背包/装备按 userId 归档, 用独立 id 避免污染其它用例 */
    private val USER_ID = 9201

    private fun e1(v: Int) = byteArrayOf((v xor 0x5A).toByte())

    private fun userInfoReqPayload(bomberIds: List<Int>): ByteArray {
        val out = ArrayList<Byte>()
        out += e1(bomberIds.size).toList()
        bomberIds.forEach { out += e1(it).toList() }
        return out.toByteArray()
    }

    private fun decodeOp198Entry(entry: PKT): Pair<Int, Int>? {
        val out = Unpooled.buffer()
        entry.encode(SendPacketBase(out, 3))
        val invCount = out.Decode1
        val result = ArrayList<Pair<Int, Int>>()
        repeat(invCount) {
            val count = out.Decode4
            repeat(count) {
                val itemId = out.Decode4
                val slot = out.Decode1
                out.Decode4
                val tokLen = out.Decode2
                out.skipBytes(tokLen)
                out.skipBytes(8)
                out.Decode4
                out.Decode1
                result += itemId to slot
            }
        }
        return result.firstOrNull { it.second == EquipSlot.CHARACTER }
    }

    @Test
    fun testCharacterCardDefsExistForAllBombers() {
        for (itemId in 1..7) {
            val def = ItemDef.find(itemId)
            assertNotNull(def, "op 60 必须包含角色卡 itemId=$itemId, 否则客户端会整条丢弃")
            assertEquals(15, def!!.category, "角色卡分类应为 15 (与抓包 itemId=1 一致)")
        }
        assertEquals("Dao", ItemDef.find(1)?.name)
        assertEquals("Boz", ItemDef.find(5)?.name, "练习房抓包里 Boz = item 5 / token Boz")
        assertEquals("Plunk", ItemDef.find(7)?.name)
    }

    @Test
    fun testOp73SyncsUserBomberOutsideRoom() {
        val cs = ClientSocket()
        val user = User(cs, USER_ID, "tester")
        cs.users += user
        assertEquals(0, user.bomber, "初始应为皮蛋 (0)")

        val buf = Unpooled.wrappedBuffer(userInfoReqPayload(listOf(7)))
        with(UserInfoReq) { buf.handle(cs) }
        assertEquals(0, buf.readableBytes())

        assertEquals(7, user.bomber, "商城/大厅换角色只靠 op 73 上报, 必须同步到 User.bomber")
    }

    @Test
    fun testMaridMapsToAdvancedForm() {
        val cs = ClientSocket()
        val user = User(cs, USER_ID + 1, "tester")
        cs.users += user

        val buf = Unpooled.wrappedBuffer(userInfoReqPayload(listOf(4)))
        with(UserInfoReq) { buf.handle(cs) }
        assertEquals(10, user.bomber, "蓝蓝 (4) 与 op 87 同约定映射到进阶形态 10")
    }

    @Test
    fun testOp198CarriesSelectedCharacter() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = User(cs, USER_ID + 2, "tester")
        cs.users += user
        ch.pipeline().addLast(cs)

        try {
            // 商城换角色: 客户端退出商城时发 op 73 (5B 5D → count=1, bomber=7)
            val req = Unpooled.wrappedBuffer(userInfoReqPayload(listOf(7)))
            with(UserInfoReq) { req.handle(cs) }

            // 回大厅重走进频道握手
            val buf = Unpooled.buffer()
            with(JoinSessionReq) { buf.handle(cs) }

            val packets = mutableListOf<PKT>()
            while (true) packets += ch.readOutbound<PKT>() ?: break
            val op198 = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 198 }
            assertNotNull(op198, "JoinSessionReq 必须下发 op 198")

            val charEntry = decodeOp198Entry(op198!!)
            assertNotNull(charEntry, "op 198 必须带角色卡条目")
            assertEquals(7, charEntry!!.first, "回大厅后角色卡必须是 Plunk (7), 不能回落到皮蛋 (1)")
            assertEquals(EquipSlot.CHARACTER, charEntry.second)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testOp274CarriesCharacterCard() {
        // op 274 的 itemId 是角色卡: chan 基准 (皮蛋) = 1, practice 基准 (宝宝) = 5。
        // 写死 1 会在 op 198 之后把客户端强行拉回 Dao —— 换角色失效的真正原因。
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = User(cs, USER_ID + 4, "tester", bomber = 5)
        cs.users += user
        ch.pipeline().addLast(cs)

        try {
            val buf = Unpooled.buffer()
            with(JoinSessionReq) { buf.handle(cs) }

            val packets = mutableListOf<PKT>()
            while (true) packets += ch.readOutbound<PKT>() ?: break
            val op274 = packets.filter { OpcodeManager.getSendOp(it::class.java) == 274 }
            assertEquals(2, op274.size, "进频道应连发两发 op 274 (玩家槽 0 / 1)")

            op274.forEachIndexed { idx, pkt ->
                val out = Unpooled.buffer()
                pkt.encode(SendPacketBase(out, 3))
                val slotType = out.Decode1
                val itemId = out.Decode4
                assertEquals(idx, slotType, "第 ${idx + 1} 发 op 274 的 slotType 应为 $idx")
                assertEquals(5, itemId, "op 274 必须带角色卡 item 5 (Boz), 不能写死 1 (Dao)")
            }
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testOp60RebuiltPerCharacter() {
        // 抓包实证 (cap/chan_s2c_op60_46885B vs cap/practice_s2c_op60_46883B, 同为 882 条):
        //   皮蛋会话: 角色卡 item 1 / name "Dao" / skinId 0, 所有 cat=3 定义 name "Dao" / skin 0
        //   宝宝会话: 角色卡 item 5 / name "Boz" / skinId 5, 所有 cat=3 定义 name "Boz" / skin 5
        val boz = ItemDef.itemsFor(5)
        assertEquals(882, boz.size, "两张角色版 op60 条目数一致 (882)")
        val bozCard = boz.firstOrNull { it.itemId == 5 }
        assertNotNull(bozCard, "宝宝版 op60 的角色卡是 itemId 5")
        assertEquals(15, bozCard!!.category)
        assertEquals("Boz", bozCard.name)
        assertEquals(5, bozCard.skinId, "角色卡的 skinId = 角色 ID")
        assertNull(boz.firstOrNull { it.itemId == 1 }, "宝宝版 op60 不该再有皮蛋卡 (item 1)")

        val bozActions = boz.filter { it.category == 3 }
        assertTrue(bozActions.isNotEmpty(), "cat=3 (表情/动作) 定义应存在")
        assertTrue(
            bozActions.all { it.name == "Boz" && it.skinId == 5 },
            "所有 cat=3 定义必须换成当前角色的模型名与 skinId"
        )

        val dao = ItemDef.itemsFor(0)
        val daoCard = dao.firstOrNull { it.itemId == 1 }!!
        assertEquals("Dao", daoCard.name)
        assertEquals(0, daoCard.skinId)
        assertTrue(dao.filter { it.category == 3 }.all { it.name == "Dao" && it.skinId == 0 })

        assertFalse(
            ItemDef.blobFor(0).contentEquals(ItemDef.blobFor(5)),
            "不同角色的 op60 数据块必须不同"
        )
    }

    @Test
    fun testProfileUsesSelectedCharacter() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = User(cs, USER_ID + 3, "tester", bomber = 7)
        cs.users += user
        ch.pipeline().addLast(cs)

        try {
            sendUserInfo(cs, 0)

            val packets = mutableListOf<PKT>()
            while (true) packets += ch.readOutbound<PKT>() ?: break
            val op73 = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 73 }
            assertNotNull(op73, "必须下发 op 73")

            val out = Unpooled.buffer()
            op73!!.encode(SendPacketBase(out, 3))
            val hex = ByteArray(out.readableBytes()).also { out.readBytes(it) }
                .joinToString("") { "%02x".format(it) }

            // itemId 7 (E4) = 0x96CA5392, slot 15 (E1) = 0x55
            assertTrue(hex.contains("96ca539255"), "名片装备列表必须含 Plunk (item 7 @ slot 15)")
            assertFalse(hex.contains("96ca539455"), "名片不能再显示皮蛋 (item 1)")
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    /**
     * S→C op 20 里带着「本机玩家槽 0 的角色 ID」。
     *
     * 两份真实抓包除该字段外逐字节一致:
     *   cap/chan_s2c_op20_46B_0.bin     (皮蛋会话) 第 29 字节 E1 = 0
     *   cap/practice_s2c_op20_44B_0.bin (宝宝会话) 第 29 字节 E1 = 5
     * 旧实现原样重放皮蛋版 → op 81 批次一到就把角色改回皮蛋。
     */
    @Test
    fun testOp20CarriesCharacter() {
        fun body(pkt: PKT): ByteArray {
            val out = Unpooled.buffer()
            pkt.encode(SendPacketBase(out, 3))
            return ByteArray(out.readableBytes()).also { out.readBytes(it) }
        }

        // 下面三份是「历史抓包回放基准」：原服在 op 20 里写 0，等级由后续 op 413 抬到 50。
        // 生产默认值已改成 50，回放时必须显式传 0 才能与 cap/*.bin 逐字节一致。
        val dao = body(UserDynamicInfo(0, bagLevel = 0))
        val boz = body(UserDynamicInfo(5, bagLevel = 0))
        assertEquals(44, dao.size, "op 20 单人版固定 44 字节")
        assertEquals(44, boz.size, "op 20 单人版固定 44 字节")

        val twoPlayers = body(UserDynamicInfo(0, bagLevel = 0, playerCount = 2))
        assertEquals(87, twoPlayers.size, "op 20 双玩家版固定 87 字节")

        // 命名/绑定回归: op 20 = 整包 UserDynamicInfo, op 122 = 单值 UserDynamicInfoValue
        assertEquals(20, OpcodeManager.getSendOp(UserDynamicInfo(0)::class.java), "op 20 必须绑定 UserDynamicInfo")
        assertEquals(122, OpcodeManager.getSendOp(UserDynamicInfoValue(0, 0)::class.java), "op 122 必须绑定 UserDynamicInfoValue")

        val diff = dao.indices.filter { dao[it] != boz[it] }
        assertEquals(listOf(28), diff, "不同角色之间只能差角色字段本身")
        assertEquals(0, (dao[28].toInt() and 0xFF) xor 0x5A)
        assertEquals(5, (boz[28].toInt() and 0xFF) xor 0x5A)

        // 生产默认必须保住背包等级 (op 20 第 13 项 = CUDI + 3s + 123)；
        // 写成 0 会让容量 = 8×0 = 0，商城购买恒报 "Your item bag is full."。
        val prodDefault = body(UserDynamicInfo(0))
        val rawDefault = Unpooled.wrappedBuffer(prodDefault)
        rawDefault.skipBytes(29)
        assertEquals(
            DEFAULT_BAG_LEVEL,
            rawDefault.readUnsignedShort() xor 0xA569,
            "op 20 默认必须带上背包等级"
        )

        File("cap/chan_s2c_op20_46B_0.bin").takeIf { it.isFile }?.let {
            val raw = it.readBytes()
            assertArrayEquals(
                dao, raw.copyOfRange(2, raw.size),
                "op 20 皮蛋版必须与真实抓包逐字节一致 (抓包文件带 2B opcode 前缀)"
            )
        }
        File("cap/practice_s2c_op20_44B_0.bin").takeIf { it.isFile }?.let {
            assertArrayEquals(boz, it.readBytes(), "op 20 宝宝版必须与真实抓包逐字节一致")
        }
    }
}
