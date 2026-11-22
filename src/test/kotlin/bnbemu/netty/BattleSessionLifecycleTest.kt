package bnbemu.netty

import bnbemu.handler.battle.BombKickThrowReq
import bnbemu.handler.battle.BomberActionReq
import bnbemu.handler.battle.BoxItemSpawnReport
import bnbemu.handler.battle.DrillReq
import bnbemu.handler.battle.ObjectHitReq
import bnbemu.handler.battle.PushMovableBoxReq
import bnbemu.handler.battle.SetBomberEventReq
import bnbemu.domain.PlayerProfile
import bnbemu.server.BattleSession
import bnbemu.server.Bomb
import bnbemu.server.Room
import bnbemu.server.state
import bnbemu.service.PlayerService
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BattleSession 生命周期与资源清理测试：
 *   - 进入战斗（Room.startGame）时创建
 *   - 回到房间（Room.backToRoom）或 Room.reset() 时销毁并置 null
 *   - 非战斗阶段收到的局内封包安全消费并忽略
 *   - 销毁后已排程的异步定时任务不再漏发包
 */
class BattleSessionLifecycleTest {

    private lateinit var ch: EmbeddedChannel
    private lateinit var cs: ClientSocket
    private var savedReenter = true

    @BeforeEach
    fun setUp() {
        cs = ClientSocket()
        ch = EmbeddedChannel(cs)
        savedReenter = Room.battleConfig.reenterRoomAfterBack
        Room.battleConfig.reenterRoomAfterBack = false
        Room.slots.forEach {
            it.clearHuman()
            it.isAI = false
            it.state = 0
        }
        val tester = PlayerService.registerProfile(PlayerProfile(userId = 1, username = "tester", charName = "tester"))
        Room.slots[0].assignHuman(tester.userId, cs)
        Room.slots[1].isAI = true
        Room.reset()
    }

    @AfterEach
    fun tearDown() {
        Room.battleConfig.reenterRoomAfterBack = savedReenter
        Room.slots.forEach {
            it.clearHuman()
            it.isAI = false
            it.state = 0
        }
        Room.reset()
        ch.finish()
    }

    @Test
    fun `handlers safely ignore packets when not in battle`() {
        assertNull(Room.battle)
        ch.outboundMessages().clear()

        // 1. op 106 放泡
        val bombBuf = Unpooled.wrappedBuffer("A5 69 5A 96 CA 53 BD 50 5B 5A 5B".toBA())
        with(SetBomberEventReq) { bombBuf.handle(cs) }
        assertEquals(0, bombBuf.readableBytes())

        // 2. op 107 踢泡
        val kickBuf = Unpooled.wrappedBuffer("3A A5 7D 58 59 58 5E A6 81".toBA())
        with(BombKickThrowReq) { kickBuf.handle(cs) }
        assertEquals(0, kickBuf.readableBytes())

        // 3. op 108 命中
        val hitBuf = Unpooled.wrappedBuffer("A7 75 5C 5F A5 09".toBA())
        with(ObjectHitReq) { hitBuf.handle(cs) }
        assertEquals(0, hitBuf.readableBytes())

        // 4. op 109 推箱
        val pushBuf = Unpooled.wrappedBuffer("A5 68 5B 59 5B 5E 5A".toBA())
        with(PushMovableBoxReq) { pushBuf.handle(cs) }
        assertEquals(0, pushBuf.readableBytes())

        // 5. op 124 电钻
        val drillBuf = Unpooled.wrappedBuffer("5F 5F 5B".toBA())
        with(DrillReq) { drillBuf.handle(cs) }
        assertEquals(0, drillBuf.readableBytes())

        // 6. op 112 action=0x60 结算
        val winBuf = Unpooled.wrappedBuffer("3A A5 39 A7 6E".toBA())
        with(BomberActionReq) { winBuf.handle(cs) }
        assertEquals(0, winBuf.readableBytes())

        // 7. op 154 action=5 死亡
        val dieBuf = Unpooled.wrappedBuffer("A5 69 A5 6C".toBA())
        with(BoxItemSpawnReport) { dieBuf.handle(cs) }
        assertEquals(0, dieBuf.readableBytes())

        assertTrue(ch.outboundMessages().isEmpty(), "非战斗期不得下发任何局内包")
    }

    @Test
    fun `battle is created on startGame and destroyed on backToRoom`() {
        assertNull(Room.battle)

        val battle = Room.startGame()
        assertNotNull(Room.battle)
        assertEquals(BattleSession.Phase.RUNNING, battle.phase)
        assertFalse(battle.isDestroyed)

        assertTrue(Room.backToRoom())
        assertNull(Room.battle, "回到房间后 Room.battle 必须置空")
        assertTrue(battle.isDestroyed, "回到房间后 BattleSession 必须被销毁")
        assertEquals(BattleSession.Phase.DESTROYED, battle.phase)
    }

    @Test
    fun `starting new game destroys previous battle and isolates state`() {
        val first = Room.startGame()
        first.onMovableBoxPush(boxSeq = 1, gridX = 1, gridY = 2, targetX = 1, targetY = 3, slotId = 0)
        assertNotNull(first.movableBoxState(1))

        val second = Room.startGame()
        assertNotSame(first, second)
        assertTrue(first.isDestroyed, "开新局时旧战斗必须被销毁")
        assertFalse(second.isDestroyed)
        assertNull(second.movableBoxState(1), "新战斗不得继承上一局的推箱状态")
    }

    @Test
    fun `destroyed battle cancels pending scheduled tasks without leaking packets`() {
        val savedDelay = Room.battleConfig.launchedBombActivationDelayMs
        try {
            Room.battleConfig.launchedBombActivationDelayMs = 80L
            val battle = Room.startGame()
            synchronized(battle.bombs) {
                battle.bombs += Bomb(40, 0, 0, 0, true).apply {
                    gridX = 5
                    gridY = 5
                }
            }
            battle.scheduleLaunchedBombActivation(40)
            battle.onObjectHit(targetObjectId = 999, gridX = 5, gridY = 5, delayMs = 80, broadcast = true)
            assertTrue(battle.launchedBombActivationPending(40))

            ch.outboundMessages().clear()
            Room.backToRoom()
            ch.outboundMessages().clear()

            Thread.sleep(160)
            assertTrue(ch.outboundMessages().isEmpty(), "销毁战斗后延时任务不得再向客户端发包")
        } finally {
            Room.battleConfig.launchedBombActivationDelayMs = savedDelay
        }
    }
}
