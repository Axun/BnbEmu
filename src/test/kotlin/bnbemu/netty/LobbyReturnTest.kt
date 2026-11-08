package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.handler.JoinSessionReq
import bnbemu.handler.UserInfoReq
import bnbemu.handler.sendUserInfo
import bnbemu.server.EquipSlot
import bnbemu.packet.UserInfoResult
import bnbemu.packet.defaultUserInventoryItems
import bnbemu.server.Room
import bnbemu.server.User
import bnbemu.server.bomber

class LobbyReturnTest {

    @Test
    fun testDefaultUserInventoryItemsDao() {
        val items = defaultUserInventoryItems(0)
        val charEntry = items.firstOrNull { it.slot == EquipSlot.CHARACTER }
        assertNotNull(charEntry)
        assertEquals(1, charEntry?.itemId, "Bomber 0 should map to itemId 1 (Dao)")
        assertEquals("Dao", charEntry?.token)
    }

    @Test
    fun testDefaultUserInventoryItemsBoz() {
        val items = defaultUserInventoryItems(5)
        val charEntry = items.firstOrNull { it.slot == EquipSlot.CHARACTER }
        assertNotNull(charEntry)
        assertEquals(5, charEntry?.itemId, "Bomber 5 should map to itemId 5 (Boz)")
        assertEquals("Boz", charEntry?.token)
    }

    @Test
    fun testDefaultUserInventoryItemsMarid() {
        val items = defaultUserInventoryItems(10)
        val charEntry = items.firstOrNull { it.slot == EquipSlot.CHARACTER }
        assertNotNull(charEntry)
        assertEquals(2082, charEntry?.itemId, "Bomber 10 should map to LuxuryMarid (2082)")
    }

    @Test
    fun testJoinSessionReqWithSelectedBomberBoz() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        val user = User(client, id = 7215, name = "ffdf", bomber = 5) // 玩家在大厅/房间选了宝宝
        client.users.add(user)
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

            // 过滤出下发的 op 198 (UserInventory)
            val op198 = sentPackets.find { OpcodeManager.getSendOp(it::class.java) == 198 }
            assertNotNull(op198, "JoinSessionReq must send op 198 (UserInventory)")

            val outBuf = Unpooled.buffer()
            op198?.encode(SendPacketBase(outBuf, 3))

            // 解码 op 198 头部:
            // Encode1(inventoryCount)
            val invCount = outBuf.Decode1
            assertTrue(invCount >= 1)

            // Encode4(itemCount)
            val itemCount = outBuf.Decode4
            assertTrue(itemCount >= 1)

            // 读取首件物品 (角色卡):
            // Encode4(itemId)
            val firstItemId = outBuf.Decode4
            // Encode1(slot)
            val firstSlot = outBuf.Decode1
            // Encode4(param)
            val firstParam = outBuf.Decode4
            // Encode2(strLen) + strBytes
            val strLen = outBuf.Decode2
            val tokenBytes = ByteArray(strLen)
            outBuf.readBytes(tokenBytes)
            val token = bnbemu.packet.decodeName(tokenBytes)

            assertEquals(EquipSlot.CHARACTER, firstSlot, "First item must be character slot (15)")
            assertEquals(5, firstItemId, "Item ID in op 198 must be 5 (Boz), NOT reverted to 1 (Dao)!")
            assertEquals("Boz", token, "Token in op 198 must be Boz!")
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testSendUserInfoInLobbyAfterReturnWithBoz() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        val user = User(client, id = 7215, name = "ffdf", bomber = 5) // 玩家当前选定角色为宝宝 (5)
        client.users.add(user)
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
            op73?.encode(SendPacketBase(outBuf, 3))

            // 验证 UserInfoResult 包含 Boz 角色装备条目: itemId 5 (96CA5390) + slot 15 (0x55), 且不含 Dao (96CA5394)
            val hex = ByteArray(outBuf.readableBytes()).also { outBuf.readBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val bozItemHex = "96ca539055"
            val daoItemHex = "96ca539455"
            assertTrue(hex.contains(bozItemHex), "UserInfoResult payload must contain Boz equip entry ($bozItemHex)")
            assertFalse(hex.contains(daoItemHex), "UserInfoResult payload must NOT contain Dao equip entry ($daoItemHex)")
        } finally {
            ch.finishAndReleaseAll()
        }
    }
}
