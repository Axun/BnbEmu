package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.packet.ChangeStage
import bnbemu.packet.EnterShopResult
import bnbemu.packet.EnterShopStage
import bnbemu.handler.shop.EnterShopReq
import bnbemu.handler.StageInitReq
import bnbemu.handler.StageStateSyncReq
import bnbemu.handler.UserInfoReq
import bnbemu.handler.sendUserInfo
import bnbemu.handler.room.ShopToRoomReq
import bnbemu.packet.ClientStage
import bnbemu.packet.PracticeSetSlotAI
import bnbemu.packet.bomberCharCard
import bnbemu.server.Room
import bnbemu.server.User
import bnbemu.server.bomber
import bnbemu.server.setBomber

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
        val bag = listOf(bnbemu.server.UserItemEntry(itemId = 710418))
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

    /** 把 S→C op 97 的 PKT 编码再解出 stageId */
    private fun readStageId(pkt: PKT): Int {
        val out = Unpooled.buffer()
        pkt.encode(SendPacketBase(out, 3))
        val inBuf = Unpooled.wrappedBuffer(out.array(), 0, out.writerIndex())
        val stageId = inBuf.Decode2
        assertEquals(0, inBuf.readableBytes(), "ChangeStage 包体只应有 2 字节")
        return stageId
    }

    @Test
    fun testShopExitFromRoomReturnsToRoom() {
        // 回归: 练习房内点商城 → 点返回, 必须回房间 (ChangeStage(12)), 不能被踢回大厅
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            cs.stage = ClientStage.Room.value // 模拟 C→S op 75 进房后 CreateSessionReq 置的舞台

            // C→S op 315: E1(tab=0) -> 5A
            val enter = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(EnterShopReq) { enter.handle(cs) }
            assertEquals(0, enter.readableBytes())
            assertEquals(ClientStage.Shop.value, cs.stage)
            assertEquals(ClientStage.Room.value, cs.shopReturnStage, "进商城前必须记住来源舞台")

            // 商城里再点一次商城按钮, 不能把来源覆盖成 14
            val enterAgain = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(EnterShopReq) { enterAgain.handle(cs) }
            assertEquals(ClientStage.Room.value, cs.shopReturnStage, "重复进商城要保留最初的来源舞台")

            while (ch.readOutbound<PKT>() != null) { /* 清掉 259/17/201 进商城回包 */ }

            // C→S op 97: E1(0) -> 5A, 点「返回」
            val exit = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(StageStateSyncReq) { exit.handle(cs) }
            assertEquals(0, exit.readableBytes())

            val pkt = ch.readOutbound<PKT>()
            assertNotNull(pkt, "退出商城必须回应 S→C op 97")
            assertEquals(97, OpcodeManager.getSendOp(pkt!!::class.java))
            assertEquals(ClientStage.Room.value, readStageId(pkt), "从房间进商城必须回 ChangeStage(12), 不能是 11")
            assertEquals(ClientStage.Room.value, cs.stage)
            assertNull(ch.readOutbound<PKT>(), "回房间路径不应额外下发回大厅的数据")
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testShopExitFromLobbyStillReturnsToLobby() {
        // 大厅进商城返回仍然回大厅, 不能被上面的修复带偏
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            cs.stage = ClientStage.Lobby.value
            val enter = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(EnterShopReq) { enter.handle(cs) }
            assertEquals(ClientStage.Lobby.value, cs.shopReturnStage)
            while (ch.readOutbound<PKT>() != null) { /* 清掉进商城回包 */ }

            val exit = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(StageStateSyncReq) { exit.handle(cs) }

            val pkt = ch.readOutbound<PKT>()
            assertNotNull(pkt)
            assertEquals(ClientStage.Lobby.value, readStageId(pkt!!))
            assertEquals(ClientStage.Lobby.value, cs.stage)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testShopToRoomReqConfirmsRoomStage() {
        // ChangeStage(12) 之后客户端会发 C→S op 80 (E2 + E4, 纯通知)
        assertEquals("ShopToRoomReq", OpcodeManager.recvOps[80])
        assertNotNull(handlerArray[80], "op 80 必须绑定 handler, 否则会刷 UNKNOWN 日志")

        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            cs.stage = ClientStage.Shop.value
            cs.shopReturnStage = ClientStage.Room.value
            val buf = Unpooled.wrappedBuffer(
                byteArrayOf(0xA5.toByte(), 0x69, 0x96.toByte(), 0xCA.toByte(), 0x53, 0x95.toByte())
            )
            with(ShopToRoomReq) { buf.handle(cs) }
            assertEquals(0, buf.readableBytes(), "E2 + E4 必须被完整消费")
            assertEquals(ClientStage.Room.value, cs.stage, "客户端已切回房间, 服务端舞台状态要跟上")
            val pkts = generateSequence { ch.readOutbound<PKT>() }.toList()
            val firstPkt = pkts.firstOrNull()
            assertNotNull(firstPkt, "切回房间后必须补推房间链 sendRoomEntryChain")
            assertTrue(pkts.any { OpcodeManager.getSendOp(it::class.java) == 83 }, "房间链必须包含 EnterSessionResult(83)")
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testShopToRoomReqIsNotShopSpecific() {
        // 二进制复核结论: op 80 只在「ChangeStage 目标为房间舞台」时发, 与来源是不是商城无关。
        // 即使 shopReturnStage 还停留在 11, 只要客户端已到达房间舞台, 服务端状态也要同步成 12。
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            cs.stage = ClientStage.Shop.value
            cs.shopReturnStage = ClientStage.Lobby.value
            val buf = Unpooled.wrappedBuffer(
                byteArrayOf(0xA5.toByte(), 0x69, 0x96.toByte(), 0xCA.toByte(), 0x53, 0x95.toByte())
            )
            with(ShopToRoomReq) { buf.handle(cs) }
            assertEquals(ClientStage.Room.value, cs.stage)
            assertNotNull(ch.readOutbound<PKT>(), "op 80 同样补推房间链 sendRoomEntryChain")
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testStageInitReqClearsRoomStage() {
        // 回大厅后客户端自动发 op 78; 之后从大厅进商城应回大厅而不是残留的房间
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            cs.stage = ClientStage.Room.value
            with(StageInitReq) { Unpooled.buffer().handle(cs) }
            assertEquals(ClientStage.Lobby.value, cs.stage)

            val enter = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(EnterShopReq) { enter.handle(cs) }
            assertEquals(ClientStage.Lobby.value, cs.shopReturnStage)
            while (ch.readOutbound<PKT>() != null) { /* 清掉回包 */ }

            val exit = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
            with(StageStateSyncReq) { exit.handle(cs) }
            val pkt = ch.readOutbound<PKT>()
            assertNotNull(pkt)
            assertEquals(ClientStage.Lobby.value, readStageId(pkt!!))
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun testOp103PacketNameIsRegistered() {
        // 改名后 EN_18_send.yaml 的 key 必须与函数名一致, 否则 OpcodeManager 扫不到 op 103
        assertEquals(103, OpcodeManager.getSendOp(PracticeSetSlotAI(1)::class.java))
    }

    @Test
    fun testShopReturnRestoresCharacterOnClient() {
        // 行为级回归: 返回房间必须把服务端权威角色回灌到客户端 CUserDynamicInfo，
        // 否则客户端带着商城舞台残留的角色值打开名片，名片就会显示错误角色。
        // 断言的是「名片最终显示的角色」，不是快照 / 忽略上报 / 重设槽位等实现细节。
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val userId = 9310
        val chosen = 7 // Plunk, 既不是默认皮蛋(0) 也不是抓包基准的宝宝(5)
        val prevUser = Room.slots[0].user
        val prevBomber = Room.slots[0].bomber.toInt()

        fun bodyOf(pkt: PKT): ByteArray {
            val out = Unpooled.buffer()
            pkt.encode(SendPacketBase(out, 3))
            return ByteArray(out.readableBytes()).also { out.readBytes(it) }
        }
        fun e1(v: Int) = byteArrayOf((v xor 0x5A).toByte())

        val user = User(cs, userId, "tester")
        cs.users += user
        user.setBomber(chosen)
        Room.slots[0].user = user
        Room.slots[0].bomber = chosen

        try {
            // 模拟客户端 CUserDynamicInfo 里残留的商城角色值 (宝宝 5)，与服务端权威角色不一致
            var clientCudiBomber = 5
            cs.stage = ClientStage.Shop.value
            cs.shopReturnStage = ClientStage.Room.value

            // 返回房间 (C→S op 80)
            with(ShopToRoomReq) {
                Unpooled.wrappedBuffer(
                    byteArrayOf(0xA5.toByte(), 0x69, 0x96.toByte(), 0xCA.toByte(), 0x53, 0x95.toByte())
                ).handle(cs)
            }
            val pkts = generateSequence { ch.readOutbound<PKT>() }.toList()
            val byOp = pkts.groupBy { OpcodeManager.getSendOp(it::class.java) }

            // 客户端只会按收到的 op 20 更新 CUserDynamicInfo；没有 op 20、或回灌值不对，
            // 它就仍带着商城残留的角色值，后续名片必然显示错误角色。
            byOp[20].orEmpty().firstOrNull()?.let { op20 ->
                clientCudiBomber = (bodyOf(op20)[28].toInt() and 0xFF) xor 0x5A
            }

            // 打开名片: 客户端上报 CUDI 里的角色，服务端同步后弹名片 (op 66 → sendUserInfo)
            with(UserInfoReq) { Unpooled.wrappedBuffer(e1(1) + e1(clientCudiBomber)).handle(cs) }
            sendUserInfo(cs, 0)

            val profile = generateSequence { ch.readOutbound<PKT>() }
                .firstOrNull { OpcodeManager.getSendOp(it::class.java) == 73 }
            assertNotNull(profile, "必须下发 S→C op 73 UserInfoResult")
            val hex = bodyOf(profile!!).joinToString("") { "%02x".format(it) }
            val expectedCard = bomberCharCard(chosen).first
            // 角色卡条目 = Encode4(itemId) + Encode1(slot 15)
            val cardHex = "%08x".format(expectedCard xor 0x96CA5395.toInt()) + "55"
            assertTrue(hex.contains(cardHex), "名片角色卡必须是 Plunk (item $expectedCard @ slot 15)")
            assertFalse(
                hex.contains("%08x".format(5 xor 0x96CA5395.toInt()) + "55"),
                "名片不能再显示宝宝 (item 5)"
            )
        } finally {
            Room.slots[0].user = prevUser
            Room.slots[0].bomber = prevBomber
            ch.finishAndReleaseAll()
        }
    }
}
