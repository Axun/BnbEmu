package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.domain.CharacterRules
import bnbemu.domain.EquipSlot
import bnbemu.domain.PlayerProfile
import bnbemu.handler.JoinSessionReq
import bnbemu.handler.user.sendUserInfo
import bnbemu.packet.user.defaultUserInventoryItems
import bnbemu.service.PlayerService

class LobbyReturnTest {

    @Test
    fun testDefaultUserInventoryItemsContainsOnlyRealStarterEquip() {
        val items = defaultUserInventoryItems()
        assertEquals(1, items.size, "默认装备清单只含 2082 进阶卡，不再包含会被丢弃的 cat=15/13 条目")
        val evoCard = items.single()
        assertEquals(CharacterRules.MARID_EVO_CARD_ITEM_ID, evoCard.itemId)
        assertEquals(EquipSlot.CHAR_DECO, evoCard.slot)
        assertTrue(items.none { it.slot == 15 }, "不应再发送会被客户端首行丢弃的 slot=15 角色卡")
    }

    @Test
    fun testJoinSessionReqWithSelectedBomberBoz() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(9401, "lobby_t1", "ffdf", bomber = 5)) // 玩家在大厅/房间选了宝宝
        client.playerId = user.userId
        ch.pipeline().addLast(client)

        try {
            // 模拟客户端返回大厅后发送 op 81 (JoinSessionReq)
            val buf = Unpooled.buffer()
            with(JoinSessionReq) {
                buf.handle(client)
            }

            val sentPackets = mutableListOf<PKT>()
            while (true) {
                val p = ch.readOutbound<PKT>() ?: break
                sentPackets.add(p)
            }

            // 1) op 20 UserDynamicInfo 必须携带权威角色 bomber=5
            val op20 = sentPackets.find { OpcodeManager.getSendOp(it::class.java) == 20 }
            assertNotNull(op20, "JoinSessionReq must send op 20 (UserDynamicInfo)")
            val op20Buf = Unpooled.buffer()
            op20!!.encode(SendPacketBase(op20Buf))
            assertEquals(5, (op20Buf.getUnsignedByte(28).toInt()) xor 0x5A, "op 20 must carry bomber=5 (Boz)")

            // 2) op 198 (UserInventory) 首件只带真实装扮 2082，不再夹带伪造角色卡
            val op198 = sentPackets.find { OpcodeManager.getSendOp(it::class.java) == 198 }
            assertNotNull(op198, "JoinSessionReq must send op 198 (UserInventory)")

            val outBuf = Unpooled.buffer()
            op198!!.encode(SendPacketBase(outBuf))
            val invCount = outBuf.Decode1
            assertTrue(invCount >= 1)
            val itemCount = outBuf.Decode4
            assertTrue(itemCount >= 1)

            val firstItemId = outBuf.Decode4
            val firstSlot = outBuf.Decode1
            assertEquals(EquipSlot.CHAR_DECO, firstSlot, "First item must be CHAR_DECO slot (4)")
            assertEquals(CharacterRules.MARID_EVO_CARD_ITEM_ID, firstItemId, "First item must be 2082")
            assertEquals(5, user.bomber, "JoinSessionReq must preserve selected bomber=5")
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testSendUserInfoInLobbyAfterReturnWithBoz() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        val user = PlayerService.registerProfile(PlayerProfile(9402, "lobby_t2", "ffdf", bomber = 5)) // 玩家当前选定角色为宝宝 (5)
        client.playerId = user.userId
        ch.pipeline().addLast(client)

        try {
            // 打开个人资料，调用 sendUserInfo
            sendUserInfo(client, 0)

            val sentPackets = mutableListOf<PKT>()
            while (true) {
                val p = ch.readOutbound<PKT>() ?: break
                sentPackets.add(p)
            }

            // 查找 S->C op 73 (UserInfoResult)
            val op73 = sentPackets.find { OpcodeManager.getSendOp(it::class.java) == 73 }
            assertNotNull(op73, "Must send op 73 (UserInfoResult)")

            val outBuf = Unpooled.buffer()
            op73!!.encode(SendPacketBase(outBuf))

            // op 73 不包含无效 slot 15 角色卡，包含真实穿戴 2082 @ CHAR_DECO (4)
            val hex = ByteArray(outBuf.readableBytes()).also { outBuf.readBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val evoCardHex = "%08x%02x".format(CharacterRules.MARID_EVO_CARD_ITEM_ID xor 0x96CA5395.toInt(), EquipSlot.CHAR_DECO xor 0x5A)
            assertTrue(hex.contains(evoCardHex), "UserInfoResult payload must contain 2082 @ CHAR_DECO ($evoCardHex)")
            assertFalse(hex.contains("96ca539055"), "UserInfoResult payload must NOT contain fake slot 15 Boz card")
            assertFalse(hex.contains("96ca539455"), "UserInfoResult payload must NOT contain fake slot 15 Dao card")
            assertEquals(5, user.bomber)
        } finally {
            ch.finishAndReleaseAll()
        }
    }
}
