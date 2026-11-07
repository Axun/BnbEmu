package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.packet.ChangeStage
import bnbemu.packet.EnterShopResult
import bnbemu.packet.EnterShopStage

class ShopTest {

    @Test
    fun testShopOpcodeBindings() {
        val recv315 = OpcodeManager.recvOps[315]
        val recv317 = OpcodeManager.recvOps[317]
        val recv37 = OpcodeManager.recvOps[37]
        val recv258 = OpcodeManager.recvOps[258]
        val recv278 = OpcodeManager.recvOps[278]

        assertEquals("EnterShopReq", recv315)
        assertEquals("ShopSyncReq", recv317)
        assertEquals("ShopUserSyncReq", recv37)
        assertEquals("ShopPackageMapReq", recv258)
        assertEquals("ShopStringTableReq", recv278)

        assertNotNull(handlerArray[315], "Handler 315 must be registered")
        assertNotNull(handlerArray[317], "Handler 317 must be registered")
        assertNotNull(handlerArray[37], "Handler 37 must be registered")
        assertNotNull(handlerArray[258], "Handler 258 must be registered")
        assertNotNull(handlerArray[278], "Handler 278 must be registered")

        val enterShopResultOp = OpcodeManager.getSendOp(EnterShopResult()::class.java)
        val enterShopStageOp = OpcodeManager.getSendOp(EnterShopStage()::class.java)

        assertEquals(259, enterShopResultOp, "EnterShopResult send opcode must be 259")
        assertEquals(17, enterShopStageOp, "EnterShopStage send opcode must be 17")
    }

    @Test
    fun testEnterShopResultPacket() {
        val pktSuccess = EnterShopResult(true)
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pktSuccess.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(buf.array(), 0, buf.writerIndex())
        val result = inBuf.Decode1
        assertEquals(1, result)

        val pktFail = EnterShopResult(false)
        val bufFail = Unpooled.buffer()
        val sendPktFail = SendPacketBase(bufFail, 3)
        pktFail.encode(sendPktFail)

        val inBufFail = Unpooled.wrappedBuffer(bufFail.array(), 0, bufFail.writerIndex())
        val resultFail = inBufFail.Decode1
        assertEquals(0, resultFail)
    }

    @Test
    fun testChangeEquipAfterPurchase() {
        // 商城购买 Bomb 655 后客户端回的 C→S op 273 (action=2):
        //   E1(2) E1(0) E2(2) [E1(0x94) E4(2082)] [E1(0xFF) E4(930000655)]
        // 注意该变体**没有**尾部 E1(在线玩家数)+E1(1)，旧实现硬读尾部会越界。
        val raw = "58 5A A5 6B CE 96 CA 5B B7 A5 A1 A4 FC 9A"
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val handler = handlerArray[273]
        assertNotNull(handler, "Handler 273 must be registered")

        val socket = ClientSocket()
        val user = bnbemu.server.User(socket, 0, "tester")
        socket.users += user

        val buf = Unpooled.wrappedBuffer(raw)
        with(handler!!) { buf.handle(socket) }

        assertEquals(0, buf.readableBytes(), "273 应被完整解析，不能因缺尾部字段抛异常")
        assertEquals(listOf(930000655), user.items.map { it.itemId }, "新买的物品应记入服务端背包")
        assertEquals(2082, user.equips[0x94], "装备槽 0x94 应记录为 2082")
    }

    @Test
    fun testBagItemsRideOnOp198() {
        // 背包物品必须并进 S→C op 198 下发，否则客户端 ReadEquippedInventory (0x67601D)
        // 清空重建时会把「商城里刚买的东西」抹掉；槽位必须是 0xFF（BAG_ONLY_SLOT），
        // 客户端 sub_65D55F 才会只塞进背包向量、不占装备槽。
        val bag = listOf(bnbemu.packet.UserItemEntry(itemId = 710418))
        val entries = bnbemu.packet.bagInventoryEntries(bag)
        assertEquals(1, entries.size)
        assertEquals(710418, entries[0].itemId)
        assertEquals(bnbemu.packet.BAG_ONLY_SLOT, entries[0].slot)
        assertEquals("Item/ShopDeco", entries[0].token, "令牌应取自 op 60 定义表")

        val inventory = bnbemu.packet.DEFAULT_USER_INVENTORY_ITEMS + entries
        val pkt = bnbemu.packet.UserInventory(listOf(inventory, inventory))
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))

        val inBuf = Unpooled.wrappedBuffer(buf.array(), 0, buf.writerIndex())
        assertEquals(2, inBuf.Decode1, "两位玩家各一份清单")
        repeat(2) {
            val count = inBuf.Decode4
            assertEquals(inventory.size, count)
            repeat(count) {
                inBuf.Decode4   // itemId
                inBuf.Decode1   // slot
                inBuf.Decode4   // param
                val tokLen = inBuf.Decode2
                inBuf.skipBytes(tokLen)  // 名字令牌
                inBuf.skipBytes(8)       // 到期时间
                inBuf.Decode4            // duration
                inBuf.Decode1            // status
            }
        }
        assertEquals(0, inBuf.readableBytes(), "op 198 包体应被完整消费")
    }

    @Test
    fun testEnterShopStagePacket() {
        val pkt = EnterShopStage()
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(buf.array(), 0, buf.writerIndex())

        val mode = inBuf.Decode1
        val subMode = inBuf.Decode1
        val f3 = inBuf.Decode4
        val f4 = inBuf.Decode4
        val f5 = inBuf.Decode4
        val catalogRev = inBuf.Decode4
        val rev2 = inBuf.Decode4
        val rev3 = inBuf.Decode4
        val msg = inBuf.DecodeStr
        val bonusCount = inBuf.Decode4

        assertEquals(0, mode)
        assertEquals(0, subMode)
        assertEquals(0, f3)
        assertEquals(0, f4)
        assertEquals(0, f5)
        assertEquals(bnbemu.packet.ShopCatalog.CATALOG_HASH, catalogRev)
        assertEquals(0, rev2)
        assertEquals(0, rev3)
        assertEquals("", msg)
        assertEquals(0, bonusCount)
        assertEquals(0, inBuf.readableBytes())
    }

    @Test
    fun testStageStateSyncOpcodeBinding() {
        // 商城退出真正发包的是 C→S op 97 (Encode1(stageState)), 不是选图用的 op 85
        assertEquals("StageStateSyncReq", OpcodeManager.recvOps[97], "C->S op 97 must be StageStateSyncReq")
        assertNotNull(handlerArray[97], "Handler 97 must be registered")
        assertSame(bnbemu.handler.StageStateSyncReq, handlerArray[97])

        // op 85 仍然是房间内选图, 不能被夺走
        assertEquals("SelectMapReq", OpcodeManager.recvOps[85])
    }

    @Test
    fun testShopExitStageStateTriggersChangeStage() {
        // 真实日志 (退出商城): payload = 5A -> Encode1(0)
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
        with(bnbemu.handler.StageStateSyncReq) { buf.handle(cs) }
        assertEquals(0, buf.readableBytes(), "1B body 必须被完整消费")

        val pkt = ch.readOutbound<PKT>()
        assertNotNull(pkt, "stageState=0 必须回应 S->C op 97 ChangeStage(11)")
        assertEquals(97, OpcodeManager.getSendOp(ChangeStage(11)::class.java))

        val out = Unpooled.buffer()
        pkt.encode(SendPacketBase(out, 3))
        // Encode2(11) with cipherDegree 3: 0x000B ^ 0xA569 = 0xA562 (BE)
        assertEquals(0xA5, out.readUnsignedByte().toInt())
        assertEquals(0x62, out.readUnsignedByte().toInt())
        assertEquals(0, out.readableBytes())

        ch.finish()
    }

    @Test
    fun testShopEnterStageStateHasNoResponse() {
        // 进商城时客户端上报 5B -> Encode1(1), 只是通知, 不应有任何回包
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0x5B.toByte()))
        with(bnbemu.handler.StageStateSyncReq) { buf.handle(cs) }
        assertEquals(0, buf.readableBytes())
        assertNull(ch.readOutbound<PKT>(), "stageState=1 不应回包")

        ch.finish()
    }

    @Test
    fun testShopExitStageStateAndChangeStagePacket() {
        // 商城退出时客户端发送: 5A (Encode1(0))
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
        val stageState = buf.Decode1
        assertEquals(0, stageState, "Decoded stageState must be 0 for shop exit")
        assertEquals(0, buf.readableBytes())

        // 服务端应答 S->C op 97 ChangeStage(11)
        val pkt = bnbemu.packet.ChangeStage(11)
        val op = OpcodeManager.getSendOp(pkt::class.java)
        assertEquals(97, op, "ChangeStage send opcode must be 97")

        val outBuf = Unpooled.buffer()
        val sendPkt = SendPacketBase(outBuf, 3)
        pkt.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(outBuf.array(), 0, outBuf.writerIndex())
        val stageId = inBuf.Decode2
        assertEquals(11, stageId, "Stage ID must be 11 (Lobby stage)")
        assertEquals(0, inBuf.readableBytes())
    }
}
