package bnbemu.netty

import bnbemu.domain.PlayerProfile
import bnbemu.handler.StageInitReq
import bnbemu.handler.StageStateSyncReq
import bnbemu.handler.lobby.ChatMsg
import bnbemu.handler.lobby.CreateSessionReq
import bnbemu.handler.lobby.RoomDetailInfoReq
import bnbemu.handler.room.ShopToRoomReq
import bnbemu.handler.shop.EnterShopReq
import bnbemu.handler.user.ChangeEquipReq
import bnbemu.handler.user.UserInfoReq
import bnbemu.packet.ClientStage
import bnbemu.server.Room
import bnbemu.service.PlayerService
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Room 实例生命周期回归测试：
 *   - 进房（CreateSessionReq / Room.enter）自动创建新实例并销毁旧实例
 *   - 退回大厅（StageInitReq op 78）自动销毁当前房间
 *   - 客户端断线（ClientSocket.channelInactive）销毁所属房间，无关短连接断开不误伤
 *   - 房间内进出商城（EnterShopReq -> StageStateSyncReq -> ShopToRoomReq）保留房间实例与状态
 *   - 大厅期 Room.current == null 时相关处理器安全兜底
 */
class RoomLifecycleTest {

    @BeforeEach
    fun setUp() {
        Room.leave()
    }

    @AfterEach
    fun tearDown() {
        Room.leave()
    }

    private fun newClient(userId: Int = 9701, charName: String = "room_tester"): Pair<EmbeddedChannel, ClientSocket> {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val user = PlayerService.registerProfile(PlayerProfile(userId = userId, username = "u$userId", charName = charName))
        cs.playerId = user.userId
        return ch to cs
    }

    private fun createSessionPayload(name: String = "TestRoom", bomber: Int = 0): ByteArray {
        val buf = Unpooled.buffer()
        val pkt = SendPacketBase(buf)
        pkt.EncodeCipherStr(name)
        pkt.EncodeCipherStr("")
        pkt.Encode1(0) // practice
        pkt.Encode1(1) // 1 local player
        pkt.Encode1(bomber)
        pkt.Encode1(15) // practice type
        pkt.Encode4(66) // ruleId
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    @Test
    fun `CreateSessionReq creates Room and replaces previous Room and BattleSession`() {
        val (ch, cs) = newClient(9701)
        try {
            with(CreateSessionReq) { Unpooled.wrappedBuffer(createSessionPayload("FirstRoom")).handle(cs) }
            val firstRoom = Room.current
            assertNotNull(firstRoom, "进房后 Room.current 必须被创建")
            firstRoom!!
            assertEquals("FirstRoom", firstRoom.name)
            assertSame(cs, firstRoom.slots[0].client)

            val firstBattle = firstRoom.startGame()
            assertFalse(firstBattle.isDestroyed)

            // 再次建房：自动销毁旧房间及其活跃 BattleSession，切换为全新 Room 实例
            with(CreateSessionReq) { Unpooled.wrappedBuffer(createSessionPayload("SecondRoom")).handle(cs) }
            val secondRoom = Room.current
            assertNotNull(secondRoom)
            assertNotSame(firstRoom, secondRoom)
            assertTrue(firstBattle.isDestroyed, "切新房间时旧房间的 BattleSession 必须被销毁")
            assertNull(firstRoom.battle)
            assertNull(firstRoom.slots[0].client, "旧房间槽位客户端引用必须被清空")
            assertEquals("SecondRoom", secondRoom!!.name)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `StageInitReq destroys active Room when returning to lobby`() {
        val (ch, cs) = newClient(9702)
        val (otherCh, otherCs) = newClient(9703, "other")
        try {
            with(CreateSessionReq) { Unpooled.wrappedBuffer(createSessionPayload("LobbyBackRoom")).handle(cs) }
            val room = Room.current!!
            val battle = room.startGame()

            // 无关客户端在大厅发 op 78 不得误伤正在房间的玩家
            with(StageInitReq) { Unpooled.buffer().handle(otherCs) }
            assertSame(room, Room.current, "其他连接发 op 78 不得销毁当前房间")
            assertFalse(battle.isDestroyed)

            // 房间成员返回大厅补发 op 78：销毁房间与战斗
            with(StageInitReq) { Unpooled.buffer().handle(cs) }
            assertNull(Room.current, "返回大厅后 Room.current 必须置空")
            assertTrue(battle.isDestroyed, "返回大厅后 BattleSession 必须被销毁")
            assertNull(room.slots[0].client)
            assertEquals(ClientStage.Lobby.value, cs.stage)
        } finally {
            otherCh.finishAndReleaseAll()
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `channelInactive destroys Room only when member socket disconnects`() {
        val (ch, cs) = newClient(9704)
        val (loginCh, _) = newClient(9704, "login_conn")
        try {
            with(CreateSessionReq) { Unpooled.wrappedBuffer(createSessionPayload("DisconnectRoom")).handle(cs) }
            val room = Room.current!!
            val battle = room.startGame()

            // 同账号的另一条短连线（如登录连线）断开不得误伤活跃房间
            loginCh.finishAndReleaseAll()
            assertSame(room, Room.current, "非房间成员连接断开不得销毁 Room")
            assertFalse(battle.isDestroyed)

            // 房间成员连线断开：自动销毁房间与战斗
            ch.finishAndReleaseAll()
            assertNull(Room.current, "成员断开连接后 Room.current 必须置空")
            assertTrue(battle.isDestroyed, "成员断开连接后 BattleSession 必须被销毁")
            assertNull(room.slots[0].client)
        } finally {
            if (loginCh.isOpen) loginCh.finishAndReleaseAll()
            if (ch.isOpen) ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `entering and exiting shop from room preserves Room instance and settings`() {
        val (ch, cs) = newClient(9705)
        try {
            with(CreateSessionReq) { Unpooled.wrappedBuffer(createSessionPayload("ShopRoom")).handle(cs) }
            val room = Room.current!!
            room.slots[2].aILevel = 4
            room.mapId = 176
            ch.outboundMessages().clear()

            // 房间内点击商城
            with(EnterShopReq) { Unpooled.wrappedBuffer(byteArrayOf(0x5A)).handle(cs) }
            assertSame(room, Room.current, "进商城不得销毁房间")
            assertEquals(ClientStage.Shop.value, cs.stage)
            assertEquals(ClientStage.Room.value, cs.shopReturnStage)

            // 从商城点返回 -> ChangeStage(12) -> ShopToRoomReq (op 80)
            with(StageStateSyncReq) { Unpooled.wrappedBuffer(byteArrayOf(0x5A)).handle(cs) }
            val shopReturnBuf = Unpooled.buffer().apply {
                SendPacketBase(this).apply {
                    Encode2(room.sessionId)
                    Encode4(room.sessionId)
                }
            }
            with(ShopToRoomReq) { shopReturnBuf.handle(cs) }

            assertSame(room, Room.current, "退出商城回房必须复用原 Room 实例")
            assertEquals(4, room.slots[2].aILevel, "房间槽位设置必须保留")
            assertEquals(176, room.mapId, "房间选图必须保留")
            assertEquals(ClientStage.Room.value, cs.stage)
        } finally {
            ch.finishAndReleaseAll()
        }
    }

    @Test
    fun `lobby handlers work safely when Room current is null`() {
        assertNull(Room.current)
        val (ch, cs) = newClient(9706, "lobby_player")
        try {
            // 1. RoomDetailInfoReq (op 67): E2(roomId) + E4(serial)
            val detailBuf = Unpooled.buffer().apply {
                SendPacketBase(this).apply {
                    Encode2(61)
                    Encode4(119)
                }
            }
            with(RoomDetailInfoReq) { detailBuf.handle(cs) }
            assertEquals(0, detailBuf.readableBytes())
            val detailReply = ch.readOutbound<PKT>()
            assertNotNull(detailReply, "大厅期查看房间详情仍需回包")
            assertEquals(85, OpcodeManager.getSendOp(detailReply!!::class.java))

            // 2. UserInfoReq (op 73): E1(1) + E1(3)
            val userInfoBuf = Unpooled.wrappedBuffer(byteArrayOf((1 xor 0x5A).toByte(), (3 xor 0x5A).toByte()))
            with(UserInfoReq) { userInfoBuf.handle(cs) }
            assertEquals(0, userInfoBuf.readableBytes())
            assertEquals(3, cs.playerId?.let { PlayerService.findByUserId(it)?.bomber })

            // 3. ChangeEquipReq (op 273): action=5 count=0
            val equipBuf = Unpooled.buffer().apply {
                SendPacketBase(this).apply {
                    Encode1(5)
                    Encode1(0)
                    Encode2(0)
                }
            }
            with(ChangeEquipReq) { equipBuf.handle(cs) }
            assertEquals(0, equipBuf.readableBytes())

            // 4. ChatMsg (op 18): "!notice hi" (E1(0) + E1(1) + EncodeCipherStr("!notice hi"))
            val chatBuf = Unpooled.buffer().apply {
                SendPacketBase(this).apply {
                    Encode1(0)
                    Encode1(1)
                    EncodeCipherStr("!notice hi")
                }
            }
            with(ChatMsg) { chatBuf.handle(cs) }
            assertEquals(0, chatBuf.readableBytes())
        } finally {
            ch.finishAndReleaseAll()
        }
    }
}
