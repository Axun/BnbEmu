package bnbemu.service

import bnbemu.data.ShopCatalogData
import bnbemu.data.ShopCurrency
import bnbemu.domain.CharacterRules
import bnbemu.domain.DEFAULT_LUCCI
import bnbemu.domain.EquipChangeEntry
import bnbemu.domain.EquipSlot
import bnbemu.domain.MAX_CASH
import bnbemu.domain.MAX_LUCCI
import bnbemu.domain.MAX_STACK_COUNT
import bnbemu.domain.StarterKit
import bnbemu.handler.AskFirstInfo
import bnbemu.handler.OnConnEstablished
import bnbemu.handler.shop.BuyItemReq
import bnbemu.handler.shop.CHARGE_LUCCI_AMOUNT
import bnbemu.handler.shop.CHARGE_NX_AMOUNT
import bnbemu.handler.shop.ChargeNxReq
import bnbemu.handler.shop.ShopUserSyncReq
import bnbemu.netty.ClientSocket
import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.OpcodeManager
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.repository.InMemoryPlayerRepository
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlayerServiceTest {

    private lateinit var repo: InMemoryPlayerRepository

    @BeforeEach
    fun setUp() {
        repo = InMemoryPlayerRepository()
        PlayerService.repository = repo
    }

    @AfterEach
    fun tearDown() {
        PlayerService.repository = InMemoryPlayerRepository()
    }

    @Test
    fun `seeded capture accounts are preserved`() {
        val kcworms1 = PlayerService.findByUsername("kcworms1")
        assertNotNull(kcworms1)
        assertEquals(7215, kcworms1!!.userId)
        assertEquals("ffdf", kcworms1.charName)

        val kcworm1 = PlayerService.findByUsername("KCWORM1")
        assertNotNull(kcworm1)
        assertEquals(7307, kcworm1!!.userId)
        assertEquals("kcworm1", kcworm1.charName)
    }

    @Test
    fun `loginOrRegister allocates distinct stable ids without hashCode collisions`() {
        val a = PlayerService.loginOrRegister("Alice")
        val b = PlayerService.loginOrRegister("Bob")
        assertNotEquals(a.userId, b.userId)
        assertEquals(a, PlayerService.loginOrRegister("alice"))
        assertEquals(a.userId, PlayerService.activeProfile.userId)
    }

    @Test
    fun `channel and shop connections share the same PlayerProfile`() {
        val channelSocket = ClientSocket()
        val channelNetty = EmbeddedChannel(channelSocket)
        val shopSocket = ClientSocket()
        val shopNetty = EmbeddedChannel(shopSocket)

        try {
            // 1. 频道连线通过 OnConnEstablished (op 7) 绑定 7215
            val op7Payload = "5B A5 6D F0 AE 35 F3 96 CA 4F BA"
                .split(" ")
                .map { it.toInt(16).toByte() }
                .toByteArray()
            with(OnConnEstablished) { Unpooled.wrappedBuffer(op7Payload).handle(channelSocket) }
            assertEquals(7215, channelSocket.playerId)

            // 2. 商城连线通过 ShopUserSyncReq (op 37) 绑定同一个 7215
            val op37Buf = Unpooled.buffer()
            SendPacketBase(op37Buf).apply {
                Encode1(2) // action
                Encode1(1) // count
                EncodeStr("ffdf")
                Encode4(7215)
                Encode1(0)
                Encode4(0)
            }
            with(ShopUserSyncReq) { op37Buf.handle(shopSocket) }
            assertEquals(7215, shopSocket.playerId)

            // 3. 商城连线充值与买道具，频道连线立即可见同一份档案
            with(ChargeNxReq) { Unpooled.buffer().handle(shopSocket) }
            assertEquals(CHARGE_NX_AMOUNT, channelSocket.cash)
            assertEquals(DEFAULT_LUCCI + CHARGE_LUCCI_AMOUNT, channelSocket.lucci)

            val buyBuf = Unpooled.buffer()
            SendPacketBase(buyBuf).apply {
                Encode4(930000655)
                Encode4(0)
                Encode1(0)
            }
            with(BuyItemReq) { buyBuf.handle(shopSocket) }
            assertTrue(channelSocket.player!!.items.any { it.itemId == 930000655 })
        } finally {
            channelNetty.finishAndReleaseAll()
            shopNetty.finishAndReleaseAll()
        }
    }

    @Test
    fun `chargeCash caps at MAX_CASH`() {
        val profile = PlayerService.loginOrRegister("cash_user")
        assertEquals(1000, PlayerService.chargeCash(profile.userId, 1000))
        assertEquals(MAX_CASH, PlayerService.chargeCash(profile.userId, MAX_CASH + 500))
    }

    @Test
    fun `chargeLucci caps at MAX_LUCCI and ChargeNxReq emits both CashBalance and UserDynamicInfoValue`() {
        val profile = PlayerService.loginOrRegister("lucci_charger")
        assertEquals(
            DEFAULT_LUCCI + CHARGE_LUCCI_AMOUNT,
            PlayerService.chargeLucci(profile.userId, CHARGE_LUCCI_AMOUNT),
        )
        assertEquals(MAX_LUCCI, PlayerService.chargeLucci(profile.userId, MAX_LUCCI + 500))

        val fresh = PlayerService.loginOrRegister("charge_both_user")
        val cs = ClientSocket().apply { playerId = fresh.userId }
        val ch = EmbeddedChannel(cs)
        try {
            with(ChargeNxReq) { Unpooled.buffer().handle(cs) }
            assertEquals(CHARGE_NX_AMOUNT, fresh.cash)
            assertEquals(DEFAULT_LUCCI + CHARGE_LUCCI_AMOUNT, fresh.lucci)

            @Suppress("UNCHECKED_CAST")
            val sent = ch.outboundMessages().toList() as List<PKT>
            assertEquals(listOf(201, 122), sent.map { OpcodeManager.getSendOp(it::class.java) })

            val op201 = Unpooled.buffer().also { sent[0].encode(SendPacketBase(it)) }
            assertEquals(CHARGE_NX_AMOUNT, op201.Decode4)

            val op122 = Unpooled.buffer().also { sent[1].encode(SendPacketBase(it)) }
            assertEquals(0, op122.Decode1)
            assertEquals(DEFAULT_LUCCI + CHARGE_LUCCI_AMOUNT, op122.Decode4)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `applyEquipChange handles equip and unequip atomically`() {
        val profile = PlayerService.loginOrRegister("equip_user")
        assertTrue(PlayerService.buyItem(profile.userId, 710418))
        assertFalse(PlayerService.buyItem(profile.userId, 710418))

        val equipRes = PlayerService.applyEquipChange(
            profile.userId,
            listOf(EquipChangeEntry(equipSlot = 220, itemId = 710418)),
        )
        assertTrue(equipRes.equipChanged)
        assertTrue(equipRes.bagChanged)
        assertEquals(710418, profile.equips[220])
        assertTrue(profile.items.none { it.itemId == 710418 })

        val unequipRes = PlayerService.applyEquipChange(
            profile.userId,
            listOf(EquipChangeEntry(equipSlot = EquipSlot.BAG, itemId = 710418)),
        )
        assertTrue(unequipRes.equipChanged)
        assertTrue(unequipRes.bagChanged)
        assertNull(profile.equips[220])
        assertTrue(profile.items.any { it.itemId == 710418 })
    }

    @Test
    fun `settleMatch updates record lucci exp and level`() {
        val profile = PlayerService.loginOrRegister("match_user")
        val initialLucci = profile.lucci
        val initialLevel = profile.level
        assertEquals(DEFAULT_LUCCI, initialLucci)

        PlayerService.settleMatch(
            userId = profile.userId,
            won = true,
            draw = false,
            expGained = 1250,
            lucciGained = 300,
        )
        assertEquals(1, profile.win)
        assertEquals(0, profile.lose)
        assertEquals(0, profile.draw)
        assertEquals(initialLevel + 1, profile.level)
        assertEquals(250, profile.exp)
        assertEquals(initialLucci + 300, profile.lucci)
    }

    @Test
    fun `askFirstInfo passes logged-in player userId rather than hardcoded 7215`() {
        val profile = PlayerService.loginOrRegister("custom_player")
        assertNotEquals(7215, profile.userId)

        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            val req = Unpooled.buffer()
            SendPacketBase(req).Encode4(0)
            with(AskFirstInfo) { req.handle(cs) }

            // op 4 直接回显登录档案的 userId（登录连线不再把 activeProfile 绑成 c.playerId）
            val pkt = ch.readOutbound<PKT>()
            assertNotNull(pkt)
            val body = Unpooled.buffer()
            pkt!!.encode(SendPacketBase(body))
            // AskFirstInfoResult -> UserInfo: E1(0), E2(scWord), E1(1), E4(permId), E4(userId)
            body.skipBytes(1 + 2 + 1 + 4)
            assertEquals(profile.userId, body.Decode4)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `character rules resolve Marid 4 to LuxuryMarid 10`() {
        assertEquals(10, CharacterRules.resolveEffectiveBomber(4))
        assertEquals(5, CharacterRules.resolveEffectiveBomber(5))
        assertEquals("Marid", CharacterRules.bomberModelName(4))
        assertEquals("Marid", CharacterRules.bomberModelName(10))
    }

    @Test
    fun `purchaseCatalogItem deducts Lucci and rejects insufficient balance or duplicate`() {
        val profile = PlayerService.loginOrRegister("lucci_buyer")
        val bomb655 = ShopCatalogData.findByProdId(930000655)!!
        assertEquals(ShopCurrency.LUCCI, bomb655.currency)
        assertEquals(1_000_000, bomb655.price)

        // 1. 首次购买成功扣款
        val ok = PlayerService.purchaseCatalogItem(profile.userId, bomb655)
        assertTrue(ok.success)
        assertEquals(DEFAULT_LUCCI - 1_000_000, profile.lucci)
        assertEquals(1, profile.items.single { it.itemId == bomb655.itemId }.count)

        // 2. 非堆叠物品重复购买被拒且不重复扣款
        val dup = PlayerService.purchaseCatalogItem(profile.userId, bomb655)
        assertEquals(PurchaseStatus.ALREADY_OWNED, dup.status)
        assertEquals(DEFAULT_LUCCI - 1_000_000, profile.lucci)

        // 3. 余额不足购买另一件高价 Lucci 物品失败
        val bomb008 = ShopCatalogData.findByProdId(2007)!! // price 500,000 Lucci
        val poor = PlayerService.purchaseCatalogItem(profile.userId, bomb008)
        assertEquals(PurchaseStatus.INSUFFICIENT_LUCCI, poor.status)
        assertEquals(DEFAULT_LUCCI - 1_000_000, profile.lucci)
        assertTrue(profile.items.none { it.itemId == bomb008.itemId })
    }

    @Test
    fun `purchaseCatalogItem deducts Cash stacks consumables and enforces limit`() {
        val profile = PlayerService.loginOrRegister("cash_buyer")
        val speaker = ShopCatalogData.findByProdId(10008)!!
        val speakerBundle = ShopCatalogData.findByProdId(1000806)!!
        assertEquals(ShopCurrency.CASH, speaker.currency)
        assertEquals(500, speaker.price)
        assertEquals(2000, speakerBundle.price)
        assertEquals(6, speakerBundle.count)

        // 1. 没点券时失败
        val noCash = PlayerService.purchaseCatalogItem(profile.userId, speaker)
        assertEquals(PurchaseStatus.INSUFFICIENT_CASH, noCash.status)
        assertTrue(profile.items.isEmpty())

        // 2. 充值后购买单件 + 6件套，验证扣点券与堆叠数量 1+6=7
        PlayerService.chargeCash(profile.userId, 5000)
        val res1 = PlayerService.purchaseCatalogItem(profile.userId, speaker)
        assertTrue(res1.success)
        assertEquals(4500, profile.cash)
        assertEquals(1, res1.itemCount)

        val res2 = PlayerService.purchaseCatalogItem(profile.userId, speakerBundle)
        assertTrue(res2.success)
        assertEquals(2500, profile.cash)
        assertEquals(7, res2.itemCount)
        assertEquals(7, profile.items.single { it.itemId == 10008 }.count)

        // 3. 超过堆叠上限被拒
        val over = PlayerService.purchaseItem(
            userId = profile.userId,
            itemId = 10008,
            count = MAX_STACK_COUNT,
            price = 0,
            currency = ShopCurrency.CASH,
            stackable = true,
        )
        assertEquals(PurchaseStatus.AMOUNT_LIMIT_EXCEEDED, over.status)
        assertEquals(7, profile.items.single { it.itemId == 10008 }.count)
    }

    @Test
    fun `BuyItemReq syncs Lucci and Cash balances and returns error op202 on failure`() {
        val profile = PlayerService.loginOrRegister("shop_flow_user")
        val cs = ClientSocket().apply { playerId = profile.userId }
        val ch = EmbeddedChannel(cs)
        try {
            fun sendBuy(prodId: Int): List<Pair<Int, ByteArray>> {
                ch.outboundMessages().clear()
                val req = Unpooled.buffer()
                SendPacketBase(req).apply {
                    Encode4(prodId)
                    Encode4(0)
                    Encode1(0)
                }
                with(BuyItemReq) { req.handle(cs) }
                @Suppress("UNCHECKED_CAST")
                return (ch.outboundMessages().toList() as List<PKT>).map { pkt ->
                    val b = Unpooled.buffer()
                    pkt.encode(SendPacketBase(b))
                    val bytes = ByteArray(b.readableBytes()).also { b.readBytes(it) }
                    OpcodeManager.getSendOp(pkt::class.java) to bytes
                }
            }

            // 1. 购买 Bomb 655 (1,000,000 Lucci) -> op 202(type=0) + op 122(234,567)
            val lucciBuy = sendBuy(930000655)
            assertEquals(listOf(202, 122), lucciBuy.map { it.first })
            assertEquals(0, Unpooled.wrappedBuffer(lucciBuy[0].second).Decode1)
            val op122Buf = Unpooled.wrappedBuffer(lucciBuy[1].second)
            assertEquals(0, op122Buf.Decode1)
            assertEquals(DEFAULT_LUCCI - 1_000_000, op122Buf.Decode4)

            // 2. 余额不足买 Cash 道具 Speaker (500 Cash) -> op 202(type=1 错误码) 且不扣款不入包
            val cashFail = sendBuy(10008)
            assertEquals(202, cashFail[0].first)
            assertEquals(1, Unpooled.wrappedBuffer(cashFail[0].second).Decode1)
            assertTrue(profile.items.none { it.itemId == 10008 })

            // 3. 充值后买 Speaker Bundle (6个, 2000 Cash) -> op 202(0) + op 122 + op 201(8000)
            PlayerService.chargeCash(profile.userId, 10_000)
            val cashOk = sendBuy(1000806)
            assertEquals(listOf(202, 122, 201), cashOk.map { it.first })
            assertEquals(0, Unpooled.wrappedBuffer(cashOk[0].second).Decode1)
            assertEquals(8000, Unpooled.wrappedBuffer(cashOk[2].second).Decode4)
            assertEquals(6, profile.items.single { it.itemId == 10008 }.count)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `new profile initializes StarterKit into unified inventory and gates Marid evolution`() {
        val profile = PlayerService.loginOrRegister("starter_user")
        assertTrue(profile.items.isEmpty(), "初始装扮均为穿戴状态，背包应为空")
        assertEquals(1, profile.inventory.size)
        assertEquals(CharacterRules.MARID_EVO_CARD_ITEM_ID, profile.equips[StarterKit.CHAR_DECO_COMPOSITE_SLOT])

        // 持有并装备 2082 进阶卡时 4 → 10；脱掉 2082 后回落 4
        assertEquals(10, CharacterRules.resolveEffectiveBomber(4, profile))
        PlayerService.applyEquipChange(
            profile.userId,
            listOf(EquipChangeEntry(EquipSlot.BAG, CharacterRules.MARID_EVO_CARD_ITEM_ID)),
        )
        assertNull(profile.equips[StarterKit.CHAR_DECO_COMPOSITE_SLOT])
        assertTrue(profile.items.any { it.itemId == CharacterRules.MARID_EVO_CARD_ITEM_ID })
        assertEquals(4, CharacterRules.resolveEffectiveBomber(4, profile))

        // 切换角色更新 PlayerProfile.bomber，不向库存塞入伪角色卡
        PlayerService.selectBomber(profile.userId, 5)
        assertEquals(5, profile.bomber)
        assertEquals(1, profile.inventory.size)
    }
}
