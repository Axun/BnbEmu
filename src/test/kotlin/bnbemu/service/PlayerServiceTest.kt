package bnbemu.service

import bnbemu.domain.DEFAULT_LUCCI
import bnbemu.domain.EquipChangeEntry
import bnbemu.domain.MAX_CASH
import bnbemu.handler.OnConnEstablished
import bnbemu.handler.shop.BuyItemReq
import bnbemu.handler.shop.CHARGE_NX_AMOUNT
import bnbemu.handler.shop.ChargeNxReq
import bnbemu.handler.shop.ShopUserSyncReq
import bnbemu.netty.ClientSocket
import bnbemu.netty.SendPacketBase
import bnbemu.repository.InMemoryPlayerRepository
import bnbemu.server.EquipSlot
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
}
