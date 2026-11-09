package bnbemu.netty

import bnbemu.handler.room.BomberActionReq
import bnbemu.packet.room.BOMBER_STATE_DEATH_LIST
import bnbemu.packet.room.ReviveBomber
import bnbemu.packet.room.GameResult
import bnbemu.packet.room.InitGameResult
import bnbemu.server.Room
import bnbemu.server.SlotMatchState
import bnbemu.server.User
import bnbemu.server.isAlive
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 对局结束判定回归测试
 *
 * 触发结算的两条路径：
 *   1. 真实路径 —— C→S op 112 action=0x60（客户端自己判定"只剩一支队伍"，每帧重发）
 *   2. 兼容路径 —— C→S op 154 action=5（只对真人玩家成立：bomber+0x4D0 = m_selfidx 闸门）
 *
 * 结算包：S→C op 169 InitGameResult → op 150 GameResult。
 * 会打出 "XXX has been resurrected!"），只留给"复活币"道具用（Room.reviveBomber）。
 */
class GameOverTest {

    private lateinit var ch: EmbeddedChannel
    private lateinit var cs: ClientSocket
    private var savedDelay = 0L
    private var savedReturnDelay = 0L

    @BeforeEach
    fun setUp() {
        cs = ClientSocket()
        ch = EmbeddedChannel(cs)
        savedDelay = Room.gameOverDelayMs
        savedReturnDelay = Room.resultReturnDelayMs
        Room.gameOverDelayMs = 0
        Room.resultReturnDelayMs = -1 // 避免前序用例调度的 3.5s BackToRoom 污染后续用例 (回房单测见 BackToRoomTest)

        // 练习房布局: slot 0 = 玩家, slot 1..7 = AI (见 CreateSessionReq)
        Room.slots.forEach { slot ->
            slot.user = null
            slot.isAI = false
        }
        Room.slots[0].user = User(cs, id = 1, name = "tester")
        for (i in 1..7) Room.slots[i].isAI = true
        Room.reset()
    }

    @AfterEach
    fun tearDown() {
        Room.gameOverDelayMs = savedDelay
        Room.resultReturnDelayMs = savedReturnDelay
        Room.slots.forEach { slot ->
            slot.user = null
            slot.isAI = false
        }
        Room.reset()
        ch.finish()
    }

    // ---- 工具 ----

    /** 等待调度线程把结算包写进 outbound 队列 (结算延迟已置 0, 但仍跨 EventLoop 线程)。 */
    private fun collectSent(timeoutMs: Long = 2000): List<PKT> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val list = ch.outboundMessages().toList() as List<PKT>
            if (list.any { opcodeOf(it) == 150 }) return list
            Thread.sleep(10)
        }
        @Suppress("UNCHECKED_CAST")
        return ch.outboundMessages().toList() as List<PKT>
    }

    private fun drain(): List<PKT> {
        @Suppress("UNCHECKED_CAST")
        return ch.outboundMessages().toList() as List<PKT>
    }

    private fun encode(pkt: PKT): ByteBuf {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        return buf
    }

    // opcode 不写在包体里, 由 Codec 在写线时按包类名从 yaml 查出并前置 (见 OpcodeManager)
    private fun opcodeOf(pkt: PKT): Int = OpcodeManager.getSendOp(pkt::class.java)

    private fun bodyOf(pkt: PKT): ByteArray {
        val buf = encode(pkt)
        val body = ByteArray(buf.readableBytes())
        buf.readBytes(body)
        return body
    }

    // 3 度帧的字段都带混淆: Encode1 ^0x5A / Encode2 ^0xA569 / Encode4 ^0x96CA5395
    private fun dec1(b: Byte): Int = (b.toInt() and 0xFF) xor 0x5A

    /** 结算三连里的 op 150 的 winnerTeam 字段 */
    private fun winnerTeamOf(sent: List<PKT>): Int = dec1(bodyOf(sent.first { opcodeOf(it) == 150 })[1])

    // ---- 包编码 ----

    @Test
    fun testPacketClassesAreBoundToOpcodes() {
        assertEquals(169, OpcodeManager.getSendOp(InitGameResult(0, 0, 0)::class.java), "InitGameResult 必须是 op 169")
        assertEquals(150, OpcodeManager.getSendOp(GameResult(0)::class.java), "GameResult 必须是 op 150")
        assertEquals(160, OpcodeManager.getSendOp(ReviveBomber(0)::class.java), "ReviveBomber 必须是 op 160")
    }

    // ---- 兼容路径: C→S op 154 action=5 ----

    @Test
    fun testKillAllEnemiesEndsMatchWithPlayerWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        // 逐个击杀 AI 1..7
        for (slot in 1..7) {
            Room.onBomberDie(slot)
            if (slot < 7) {
                assertFalse(
                    drain().any { opcodeOf(it) == 150 },
                    "还有 AI 存活时不得结算 (slot=$slot 阵亡后)"
                )
            }
        }

        val sent = collectSent()
        val ops = sent.map(::opcodeOf)
        assertTrue(ops.contains(169), "AI 全灭必须下发 op 169 InitGameResult, ops=$ops")
        assertTrue(ops.contains(150), "AI 全灭必须下发 op 150 GameResult, ops=$ops")
        assertEquals(0, winnerTeamOf(sent), "玩家胜利时 winnerTeam 必须为 0")
    }

    @Test
    fun testPlayerDeathStillEndsMatchWithAiWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(0)
        val sent = collectSent()
        val ops = sent.map(::opcodeOf)

        assertTrue(ops.contains(159), "必须先广播 op 159 BomberDie, ops=$ops")
        assertTrue(ops.contains(169) && ops.contains(150), "结算包缺失, ops=$ops")
        assertEquals(1, winnerTeamOf(sent), "AI 胜利时 winnerTeam 必须为 1")
    }

    @Test
    fun testMatchContinuesWhileBothSidesHaveSurvivors() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(1)
        Room.onBomberDie(2)
        Thread.sleep(120)

        val ops = drain().map(::opcodeOf)
        assertTrue(ops.contains(159), "阵亡必须广播 op 159, ops=$ops")
        assertFalse(ops.contains(150), "双方都还有人时不得结算, ops=$ops")
        assertFalse(Room.gameOverSent)
    }

    @Test
    fun testDuplicateDeathReportIsIgnored() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(3)
        Room.onBomberDie(3) // 客户端 OnTick 重发 / 残流
        Room.onBomberDie(3)

        val ops = drain().map(::opcodeOf)
        assertEquals(1, ops.count { it == 159 }, "同一槽位重复阵亡上报只能广播一次, ops=$ops")
    }

    @Test
    fun testResultIsSentOnlyOnce() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(0)
        collectSent()
        val first = drain().count { opcodeOf(it) == 150 }
        assertEquals(1, first, "结算只能下发一次")

        // 迟到的其他阵亡上报不得再触发第二套结算
        Room.onBomberDie(4)
        Thread.sleep(120)
        assertEquals(1, drain().count { opcodeOf(it) == 150 }, "结算后不得重复下发 op 150")
    }

    @Test
    fun testAliveStateIsResetOnNextMatch() {
        Room.startGame()
        for (slot in 1..7) Room.onBomberDie(slot)
        collectSent()
        assertTrue(Room.gameOverSent, "第一局应当已结算")

        // 第二局: 所有参战槽位重新复活, 计数与结算标志都要复位
        Room.startGame()
        assertFalse(Room.gameOverSent, "新一局必须清空结算标志")
        assertTrue(Room.slots[1].isAlive && Room.slots[7].isAlive, "新一局所有 AI 必须复活")
        assertTrue(Room.slots[0].isAlive, "新一局玩家必须复活")

        ch.outboundMessages().clear()
        Room.onBomberDie(1)
        Thread.sleep(120)
        val ops = drain().map(::opcodeOf)
        assertTrue(ops.contains(159), "新一局首次阵亡上报不能被当成重复上报吞掉, ops=$ops")
        assertFalse(ops.contains(150))
    }

    @Test
    fun testUnknownSlotIsDropped() {
        Room.startGame()
        ch.outboundMessages().clear()
        Room.onBomberDie(8)
        Room.onBomberDie(-1)
        assertTrue(drain().isEmpty(), "越界槽位不得广播也不得触发结算")
        assertFalse(Room.gameOverSent)
    }

    @Test
    fun testEmptySlotIsAlwaysOutOfMatch() {
        // slot 5 没座位
        Room.slots[5].isAI = false
        Room.startGame()
        assertEquals(SlotMatchState.OUT_OF_MATCH, Room.slots[5].matchState)
        assertEquals(SlotMatchState.ALIVE, Room.slots[6].matchState, "AI 槽必须是 ALIVE")
        assertFalse(Room.slots[5].isAlive)
        ch.outboundMessages().clear()

        // 空槽的错位/迟到阵亡上报必须被丢弃：不能给没有 bomber 的槽位广播 op 159
        Room.onBomberDie(5)
        assertTrue(drain().isEmpty(), "空槽阵亡上报不得广播 op 159")
        assertEquals(SlotMatchState.OUT_OF_MATCH, Room.slots[5].matchState, "空槽状态不得被上报改写")
        assertFalse(Room.gameOverSent)
    }

    @Test
    fun testDeathOnlyTransitionsFromAlive() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(2)
        assertEquals(SlotMatchState.DEAD, Room.slots[2].matchState)
        Room.onBomberDie(2)
        assertEquals(SlotMatchState.DEAD, Room.slots[2].matchState, "DEAD 是终态, 重复上报不得改变状态")
        assertEquals(1, drain().count { opcodeOf(it) == 159 }, "只有一次 ALIVE→DEAD 才广播")
    }

    @Test
    fun testPureHumanRoomDoesNotEndOnFirstDeath() {
        // 将来若开纯人类 PvP 房 (无 AI), 死一个人不能被误判成"AI 全灭"而立刻结算
        for (i in 1..7) Room.slots[i].isAI = false
        Room.slots[1].user = User(cs, id = 2, name = "tester2")
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(0)
        Thread.sleep(120)
        val ops = drain().map(::opcodeOf)
        assertTrue(ops.contains(159), "阵亡仍要广播 op 159, ops=$ops")
        assertFalse(ops.contains(150), "对手还活着时不得结算, ops=$ops")
        assertFalse(Room.gameOverSent)
    }

    // ---- 真实路径: C→S op 112 action=0x60 ----

    @Test
    fun testHandlerBindsOp112() {
        assertEquals("BomberActionReq", OpcodeManager.recvOps[112])
        assertSame(BomberActionReq, handlerArray[112])
    }

    /**
     * 实战日志原样样本: `3A A5 39 A7 6E`
     *   E1(0x3A ^ 0x5A = 0x60) → action 0x60 / slot 0
     *   E2(0xA539 ^ 0xA569 = 0x0050) → x = 80
     *   E2(0xA76E ^ 0xA569 = 0x0207) → y = 519
     */
    @Test
    fun testCapturedAction60BodyEndsMatchWithPlayerWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        val raw = byteArrayOf(0x3A, 0xA5.toByte(), 0x39, 0xA7.toByte(), 0x6E)
        val buf = Unpooled.wrappedBuffer(raw)
        with(BomberActionReq) {
            buf.handle(cs)
        }
        assertEquals(0, buf.readableBytes(), "5B body 必须被完整消费")

        val sent = collectSent()
        val ops = sent.map(::opcodeOf)
        assertTrue(ops.contains(169) && ops.contains(150), "结算包缺失, ops=$ops")
        assertEquals(0, winnerTeamOf(sent), "存活方是玩家槽 → winnerTeam = 0")
    }

    @Test
    fun testAction60DoesNotReplayDeathAnimation() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onLastTeamStanding(0)
        Thread.sleep(120)

        val ops = drain().map(::opcodeOf)
        assertFalse(ops.contains(159), "AI 的死亡动画客户端本地已经播过, 不得再广播 op 159, ops=$ops")
        assertEquals(SlotMatchState.DEAD, Room.slots[1].matchState, "落败方要补记 DEAD")
        assertTrue(Room.slots[0].isAlive, "存活方保持 ALIVE")
    }

    @Test
    fun testRepeatedAction60EndsMatchOnlyOnce() {
        Room.startGame()
        ch.outboundMessages().clear()

        // 客户端每帧重发, 实测一秒能来几百条
        repeat(50) { Room.onLastTeamStanding(0) }

        val sent = collectSent()
        assertEquals(1, sent.count { opcodeOf(it) == 150 }, "action=0x60 洪水只能触发一次结算")
        repeat(5) { Room.onLastTeamStanding(0) }
        Thread.sleep(120)
        assertEquals(1, drain().count { opcodeOf(it) == 150 }, "结算后不得重复下发 op 150")
    }

    @Test
    fun testAction60FromAiSlotGivesAiWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        // 玩家先阵亡 (op154 action=5 只对真人成立, 这条路径仍然有效),
        // 之后客户端会替存活的 AI 发 action=0x60 —— 但结算此时早就发过了, 不能重发
        Room.onBomberDie(0)
        val first = collectSent()
        assertEquals(1, first.count { opcodeOf(it) == 150 }, "玩家阵亡立刻结算")
        assertEquals(1, winnerTeamOf(first), "AI 胜, winnerTeam=1")

        ch.outboundMessages().clear()
        Room.onLastTeamStanding(3)
        Thread.sleep(120)
        assertTrue(drain().isEmpty(), "已经结算过, 迟到的 action=0x60 不得再下发任何包")
    }

    @Test
    fun testAction60FromEmptySlotIsIgnored() {
        Room.startGame()
        ch.outboundMessages().clear()

        // slot 5 是空槽 (练习房只有 slot 0 + 1..7 AI? 这里清掉一个座位来模拟空槽)
        Room.slots[5].isAI = false
        Room.onLastTeamStanding(5)

        Thread.sleep(120)
        assertTrue(drain().isEmpty(), "空槽的错位上报不得触发结算")
        assertFalse(Room.gameOverSent)
        Room.slots[5].isAI = true
    }

    @Test
    fun testBomberDieBroadcastsDeathListToo() {        // 阵亡 = op159（状态 17/清队伍桶，实测无音效）+ op146 state5 死亡名单（摘除 + 音效 0x2A）
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(1)
        val sent = drain()
        val ops = sent.map(::opcodeOf)
        assertEquals(1, ops.count { it == 159 }, "先发 op 159, ops=$ops")
        val list = sent.firstOrNull { opcodeOf(it) == 146 }
        assertNotNull(list, "必须同时下发死亡名单, ops=$ops")
        val body = bodyOf(list!!)
        assertEquals(BOMBER_STATE_DEATH_LIST, dec1(body[0]) shr 4, "state 必须是 5")
        assertEquals(1, dec1(body[5]), "名单里 1 个槽位")
        assertEquals(1, dec1(body[6]), "槽位 1")
        assertTrue(ops.indexOf(159) < ops.indexOf(146), "159 必须在名单之前（名单摘掉 bomber 后 159 会提前返回）")
    }

    // ---- 结果页字段（2026-11-08 逆向定案，见 packet/room/GameResult.kt 注释） ----

    private fun dec2(b: ByteArray, off: Int): Int =
        (((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)) xor 0xA569

    private fun dec4(b: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (b[off + i].toInt() and 0xFF)
        return v xor 0x96CA5395.toInt()
    }

    @Test
    fun testGameResultResultScreenFields() {
        // 玩家胜：真人 = WIN 帧、AI = LOSE 帧；gameType 必须 0
        val body = bodyOf(GameResult(winnerTeam = 0, delayMs = 2000))
        assertEquals(119, body.size, "op150 包体长度必须仍是 119B")
        assertEquals(0, dec1(body[0]), "header.gameType 必须 0 —— 非 0 会多盖一个 MISSION PERFECT!! 印章")
        assertEquals(0, dec1(body[1]), "header.winnerTeam = 0 (玩家胜)")

        // Human 槽从 body[11] 开始，各字段偏移与 0x00541205 的读取顺序一致
        val human = 11
        assertEquals(1, dec1(body[human]), "slotType = 1 (Human)")
        assertEquals(0, dec1(body[human + 5]), "胜方 +68 = YOU WIN 帧(0)")
        assertEquals(0, dec2(body, human + 31), "+124 战前 EXP 进度")
        assertEquals(50, dec2(body, human + 33), "+128 战后 EXP 进度 (=EXP% 列数据源)")
        assertEquals(5, dec2(body, human + 35), "+132 房间槽位 / CUserMap 玩家等级必须保留 (>=1)")
        assertEquals(5, dec2(body, human + 37), "+136 CUserDynamicInfo 战后等级必须保留 (>=1)，写 0 会导致回房触发 InvalidLevelPlayGame")
        assertEquals(100, dec4(body, human + 49), "+152 LUCCI 列")

        // 第一个 AI 槽：输的一方必须是 LOSE 帧，写 0 会显示成 "YOU WIN!!"
        val ai = human + 64
        assertEquals(2, dec1(body[ai]), "slotType = 2 (AI)")
        assertEquals(2, dec1(body[ai + 1]), "败方 +68 = LOSE 帧(2)")
    }

    @Test
    fun testGameResultAiWinSwapsFrames() {
        // AI 胜：真人 = LOSE 帧、AI = WIN 帧
        val body = bodyOf(GameResult(winnerTeam = 1))
        assertEquals(2, dec1(body[11 + 5]), "玩家阵亡 → 真人 +68 = LOSE 帧(2)")
        assertEquals(0, dec1(body[11 + 64 + 1]), "AI 胜 → AI 槽 +68 = WIN 帧(0)")
        assertEquals(1, dec1(body[1]), "header.winnerTeam = 1 (AI 胜)")
    }

    // ---- 结算奖励: 输的一档必须低于赢的一档 ----

    @Test
    fun testWinRewards() {
        Room.startGame()
        ch.outboundMessages().clear()
        Room.onLastTeamStanding(0)

        val sent = collectSent()
        val body = bodyOf(sent.first { opcodeOf(it) == 150 })
        assertEquals(Room.winExpAfter, dec2(body, 11 + 33), "玩家胜 → +128 EXP = winExpAfter")
        assertEquals(Room.winLucci, dec4(body, 11 + 49), "玩家胜 → +152 LUCCI = winLucci")
        assertTrue(Room.loseExpAfter < Room.winExpAfter, "失败 EXP 必须低于胜利")
        assertTrue(Room.loseLucci < Room.winLucci, "失败 LUCCI 必须低于胜利")
    }

    @Test
    fun testLoseRewardsAreLower() {
        Room.startGame()
        ch.outboundMessages().clear()
        Room.onBomberDie(0) // 玩家阵亡 → AI 胜

        val sent = collectSent()
        val body = bodyOf(sent.first { opcodeOf(it) == 150 })
        assertEquals(1, dec1(body[1]), "AI 胜")
        assertEquals(Room.loseExpAfter, dec2(body, 11 + 33), "AI 胜 → 玩家 +128 EXP = loseExpAfter")
        assertEquals(Room.loseLucci, dec4(body, 11 + 49), "AI 胜 → 玩家 +152 LUCCI = loseLucci")
    }

    @Test
    fun testGameResultPreservesCustomPlayerLevelAndClampsZero() {
        val human = 11
        Room.slots[0].user = User(cs, id = 1, name = "tester", level = 12)
        val body12 = bodyOf(GameResult(winnerTeam = 1))
        assertEquals(12, dec2(body12, human + 35), "+132 必须写入 User.level")
        assertEquals(12, dec2(body12, human + 37), "+136 必须写入 User.level")

        // 即使 User.level 异常为 0，也必须钳制到 >= 1，防止 CUserDynamicInfo 等级被写成 0 触发 sub_67CAC3(1) 踢回大厅
        Room.slots[0].user = User(cs, id = 1, name = "tester", level = 0)
        val bodyClamped = bodyOf(GameResult(winnerTeam = 1))
        assertTrue(dec2(bodyClamped, human + 35) >= 1, "+132 绝不可为 0")
        assertTrue(dec2(bodyClamped, human + 37) >= 1, "+136 绝不可为 0")
    }
}
