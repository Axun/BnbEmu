package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.data.ItemDefCatalog
import bnbemu.domain.CharacterRules
import bnbemu.domain.EquipSlot
import bnbemu.domain.PlayerProfile
import bnbemu.handler.JoinSessionReq
import bnbemu.handler.user.UserInfoReq
import bnbemu.handler.user.sendUserInfo
import bnbemu.packet.user.DEFAULT_BAG_LEVEL
import bnbemu.packet.user.UserDynamicInfo
import bnbemu.packet.user.UserDynamicInfoValue
import bnbemu.service.PlayerService
import java.io.File

/**
 * 「商城里换角色 → 返回大厅后角色保持权威值」的回归测试。
 *
 * - 客户端通过 `C→S op 73` 上报当前角色、`JoinSessionReq` 通过 `S→C op 20` (`CUDI+3s+111`)
 *   与 `S→C op 60` (`cat=3` 表情/动作模型定义) 保持选角状态。
 * - `cat=15` 基础角色卡 (`itemId=1 Dao` 等) 会被客户端 `sub_65C81A` 首行直接丢弃，
 *   服务端不再伪造 `2..7` 角色卡也不在 `op 198`/`op 274`/`op 73` 中塞入 `slot=15` 条目。
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

    private fun decodeOp198Entries(entry: PKT): List<Pair<Int, Int>> {
        val out = Unpooled.buffer()
        entry.encode(SendPacketBase(out))
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
        return result
    }

    @Test
    fun testBaseCatalogKeepsCapturedItem1AndNoFakeCards() {
        val daoDef = ItemDefCatalog.find(1)
        assertNotNull(daoDef, "基础表应保留抓包原始 itemId=1 (Dao)")
        assertEquals(15, daoDef!!.category)
        assertEquals("Dao", daoDef.name)

        // 进阶卡 2082 必须启用为 cat=16 (sub_67884E(16)==6), 目标基础角色 4 (Marid) -> skinId 10
        val maridEvo = ItemDefCatalog.find(CharacterRules.MARID_EVO_CARD_ITEM_ID)
        assertNotNull(maridEvo)
        assertEquals(0x10, maridEvo!!.category)
        assertEquals(4, maridEvo.targetCharId)
        assertEquals(10, maridEvo.skinId)
    }

    @Test
    fun testOp73SyncsUserBomberOutsideRoom() {
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(USER_ID, "char_t1", "tester"))
        cs.playerId = user.userId
        assertEquals(0, user.bomber, "初始应为皮蛋 (0)")

        val buf = Unpooled.wrappedBuffer(userInfoReqPayload(listOf(7)))
        with(UserInfoReq) { buf.handle(cs) }
        assertEquals(0, buf.readableBytes())

        assertEquals(7, user.bomber, "商城/大厅换角色只靠 op 73 上报, 必须同步到 PlayerProfile.bomber")
    }

    @Test
    fun testMaridMapsToAdvancedForm() {
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(USER_ID + 1, "char_t2", "tester"))
        cs.playerId = user.userId

        val buf = Unpooled.wrappedBuffer(userInfoReqPayload(listOf(4)))
        with(UserInfoReq) { buf.handle(cs) }
        assertEquals(10, user.bomber, "蓝蓝 (4) 与 op 87 同约定映射到进阶形态 10")
    }

    @Test
    fun testJoinSessionSyncsSelectedCharacterViaOp20AndOp60() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(USER_ID + 2, "char_t3", "tester"))
        cs.playerId = user.userId
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

            // 1) op 20 UserDynamicInfo 必须携带权威角色 bomber=7 (Plunk)
            val op20 = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 20 }
            assertNotNull(op20, "JoinSessionReq 必须下发 op 20")
            val op20Buf = Unpooled.buffer()
            op20!!.encode(SendPacketBase(op20Buf))
            assertEquals(7, (op20Buf.getUnsignedByte(28).toInt()) xor 0x5A, "op 20 必须携带 Plunk (7)")

            // 2) op 198 只下发真实穿戴物 (2082 @ CHAR_DECO)，不塞入 slot=15 伪角色卡
            val op198 = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 198 }
            assertNotNull(op198, "JoinSessionReq 必须下发 op 198")
            val entries = decodeOp198Entries(op198!!)
            assertTrue(entries.none { it.second == 15 }, "op 198 不应再发送会被丢弃的 slot=15 角色卡")
            assertTrue(entries.any { it.first == CharacterRules.MARID_EVO_CARD_ITEM_ID && it.second == EquipSlot.CHAR_DECO })

            // 3) JoinSessionReq 不再下发旧版 op 274 伪角色卡更新
            assertTrue(packets.none { OpcodeManager.getSendOp(it::class.java) == 274 })
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testOp60RebuiltPerCharacter() {
        // 宝宝会话: 所有 cat=3 表情/动作定义换成 name "Boz" / skin 5，其余条目保留底表
        val boz = ItemDefCatalog.itemsFor(5)
        assertEquals(882, boz.size, "两张角色版 op60 条目数一致 (882)")

        val bozActions = boz.filter { it.category == 3 }
        assertTrue(bozActions.isNotEmpty(), "cat=3 (表情/动作) 定义应存在")
        assertTrue(
            bozActions.all { it.name == "Boz" && it.skinId == 5 },
            "所有 cat=3 定义必须换成当前角色的模型名与 skinId"
        )

        val dao = ItemDefCatalog.itemsFor(0)
        val daoBaseCard = dao.firstOrNull { it.itemId == 1 }!!
        assertEquals("Dao", daoBaseCard.name)
        assertEquals(0, daoBaseCard.skinId)
        assertTrue(dao.filter { it.category == 3 }.all { it.name == "Dao" && it.skinId == 0 })

        assertFalse(
            ItemDefCatalog.blobFor(0).contentEquals(ItemDefCatalog.blobFor(5)),
            "不同角色的 op60 数据块必须不同"
        )
    }

    @Test
    fun testProfileExcludesFakeCharCardAndKeepsBomber() {
        val ch = EmbeddedChannel()
        val cs = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(USER_ID + 3, "char_t4", "tester", bomber = 7))
        cs.playerId = user.userId
        ch.pipeline().addLast(cs)

        try {
            sendUserInfo(cs, 0)

            val packets = mutableListOf<PKT>()
            while (true) packets += ch.readOutbound<PKT>() ?: break
            val op73 = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 73 }
            assertNotNull(op73, "必须下发 op 73")

            val out = Unpooled.buffer()
            op73!!.encode(SendPacketBase(out))
            val hex = ByteArray(out.readableBytes()).also { out.readBytes(it) }
                .joinToString("") { "%02x".format(it) }

            val evoCardHex = "%08x%02x".format(CharacterRules.MARID_EVO_CARD_ITEM_ID xor 0x96CA5395.toInt(), EquipSlot.CHAR_DECO xor 0x5A)
            assertTrue(hex.contains(evoCardHex), "名片装备列表必须含真实穿戴的 2082 进阶卡")
            assertFalse(hex.contains("96ca539255"), "名片装备列表不应包含伪造的 slot 15 角色卡 (Plunk)")
            assertFalse(hex.contains("96ca539455"), "名片装备列表不应包含伪造的 slot 15 角色卡 (Dao)")
            assertEquals(7, user.bomber)
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
            pkt.encode(SendPacketBase(out))
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

        // 第 8 栏 = Lucci (记录偏移 15 的 E4, CUDI + 3s + 12)：必须按传入值下发。
        // 这一栏是整包快照，发 0 会把 op 122 刚同步的金币清零（两者是同一个 setter）。
        val withLucci = body(UserDynamicInfo(0, lucci = 5000))
        val lucciBuf = Unpooled.wrappedBuffer(withLucci)
        lucciBuf.skipBytes(15)   // body 内偏移 15（0 = count 字节，表里的「偏移」即 body 偏移）
        assertEquals(
            5000,
            lucciBuf.readInt() xor 0x96CA5395.toInt(),
            "op 20 第 8 栏必须是 Lucci"
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
