package bnbemu.server

import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.data.MapCatalog
import bnbemu.domain.CharacterRules
import bnbemu.domain.PlayerProfile
import bnbemu.netty.ClientSocket
import bnbemu.netty.NettyServer.workerGroup
import bnbemu.netty.PKT
import bnbemu.packet.ClientStage
import bnbemu.packet.room.PracticeSetSlotAI
import bnbemu.packet.room.PracticeSlotReady
import bnbemu.packet.lobby.SESSION_TYPE_BATTLE
import bnbemu.packet.battle.BackToRoom
import bnbemu.packet.battle.GameResultSlotSnapshot
import bnbemu.packet.room.SetPlayer
import bnbemu.packet.room.SetSlotState
import bnbemu.packet.user.UserDynamicInfoValue
import bnbemu.service.PlayerService
import bnbemu.service.player
import io.netty.channel.EventLoop
import org.tinylog.kotlin.Logger

// 之後改用class
object Room {
    private val executor: EventLoop = workerGroup.next()
    private val battleLock = Any()

    val slots = Array(8) { Slot() }
    var mapId = 89
    var themeId = 0

    /** 默认战斗配置；每次开局创建 [BattleSession] 时传入快照。 */
    val battleConfig = BattleConfig()

    /** 当前活跃的战斗会话；仅在进入战斗（[startGame]）时创建，回到房间（[backToRoom] / [reset]）时销毁并置 null。 */
    @Volatile
    var battle: BattleSession? = null
        private set

    /**
     * 房间号 / 会话 id —— S→C op 83 [bnbemu.packet.lobby.EnterSessionResult] 的第 3 栏位。
     *
     * 客户端 (CA.exe 0x00506513) 拿这个 id 去 CLobbyStage 的房屋缓存数组 (由 op 87 房间列表填充)
     * 里找同一间房, **找不到就整段跳过**: 房间名/等级/人数不会写进 CFxContext, 只有
     * 「进房成功 + 切房间场景」这一步照常发生。所以它只影响 UI 的会话信息刷新, 不影响进房本身。
     *
     * 默认 75 = tst.pcap stream4 里真实服务器给练习房发的房号 (op 87 房间列表条目里同一个号),
     * 保持与抓包逐字节一致 (replay_test 的 practice 用例对 op 83 载荷做逐字节断言)。
     */
    var sessionId = DEFAULT_SESSION_ID
    const val DEFAULT_SESSION_ID = 75

    // ---- C→S op 75 会话请求快照 (2026-11-08) ----
    // EN 客户端的练习房 / 普通房 / 快速建房等 8 个发包点共用 op 75, 靠这几个值区分。
    // 目前回应链仍只按练习房基准实现, 这里先完整记录请求, 等普通房 S→C 抓包钉死线序后
    // 再按 sessionMode 分叉 (见 handler/CreateSessionReq.kt 顶部注释)。
    /** sub_517233 的 a4: 0 = 练习 / AI 房, 1 = 普通房 */
    var sessionMode = 0

    /** a5 房型: 15 = 练习房, 0 = 普通房; 需原样回显到 S→C op 83 */
    var sessionType = 15

    /** sub_516C5A 按房型查表算出的规则 id: 15 → 66, 0 → 0 */
    var ruleId = 66

    /** 房间名 / 密码明文 (线上是 EncodeCipherStr 的 3 度混淆串) */
    var name = ""
    var password = ""

    /** 每个本地玩家一个 bomber id, 长度 = 玩家数, 不再固定读 1 个 */
    var localBombers: List<Int> = emptyList()

    val isAIBattle: Boolean
        get() = sessionType == SESSION_TYPE_BATTLE

    fun npcDefaultBomberForSlot(slotIdx: Int): Int = 20 + (slotIdx - 4).coerceAtLeast(0)

    /* 激战模式的特殊NPC */
    fun isBattleNpcBomber(bomber: Int): Boolean = bomber in 20..25

    fun chiefSlotIndex(): Int =
        slots.indexOfFirst { it.isHuman && (it.state.toInt() and FLAG_CHIEF) != 0 }
            .takeIf { it >= 0 } ?: 0

    /**
     * 按激战模式 (sessionType=16) 布局初始化 8 个槽位:
     *   - 0 号槽: 真人房主 (`FLAG_CHIEF`)
     *   - 1..3 号槽: 真人空位默认封闭 (`FLAG_CLOSED`, `isAI=false`, 不放机器人)
     *   - 4..7 号槽: NPC 敌方队伍 (`isAI=true`, 默认角色 `20..23`, 难度 `DEFAULT_AI_LEVEL`)
     */
    fun setupAiBattleSlots(
        player: PlayerProfile?,
        client: ClientSocket? = null,
        requestedBombers: List<Int> = emptyList(),
    ) {
        if (player != null) {
            val requestedBomber = requestedBombers.firstOrNull() ?: player.bomber
            val chosenBomber = if (requestedBomber in 0..15) requestedBomber else player.bomber
            val effectiveBomber = CharacterRules.resolveEffectiveBomber(chosenBomber, player)
            PlayerService.selectBomber(player.userId, effectiveBomber)

            slots[0].apply {
                assignHuman(player.userId, client)
                isAI = false
                state = FLAG_CHIEF
                bomber = effectiveBomber
                color = 0
            }
        }

        for (i in 1..3) {
            slots[i].apply {
                clearHuman()
                isAI = false
                state = FLAG_CLOSED
                aILevel = 0
                bomber = 0
                color = 0
            }
        }

        for (i in 4..7) {
            slots[i].apply {
                clearHuman()
                isAI = true
                state = 0
                aILevel = DEFAULT_AI_LEVEL
                bomber = npcDefaultBomberForSlot(i)
                color = 7
            }
        }
    }

    val activeSlotCount: Int
        get() = slots.count { it.isHuman || it.isAI }

    val uniqueClients: Sequence<ClientSocket>
        get() = slots.asSequence()
            .mapNotNull { it.client } // 取得所有slot中有玩家的 ClientSocket
            .filter { runCatching { it.ch }.isSuccess && runCatching { it.ctx }.isSuccess }  // 初始化，防止在单元测试等场景抛出异常
            .distinctBy { it.ch.id() } // 過濾掉 2P 的重複連線 對2P只會發一次

    // ========================================================================
    // 地图人数上限 → 更换地图后自动踢出超额的电脑玩家 (2026-11-12)
    // ========================================================================
    // 真端选图时若房间人数超过地图 maxPlayer, 只会在 S→C op 108 回 result=1,
    // 由客户端 (CA.exe 0x4F09D9) 弹 "MapSelectExceedMaxPlayer" 拒绝选图, 要玩家自己踢人。
    // 模拟器改为服务端直接把超额的 AI 从高槽位起踢掉, 并同步客户端房间界面:
    //   S→C op 106 PracticeSlotReady(state=1) + op 103 PracticeSetSlotAI(isAI=false)
    // —— 与 C→S op 89「踢出电脑敌人」完全同一套 (见 handler/room/OpenCloseSlotReq.kt),
    //    103 还会清掉 CPrepareStrategyAIBattle 的 [esi+0x3250] 等待锁。
    //
    // · 真人槽永不进踢出候选; 查不到地图模板 (随机图按钮等) 时 maxPlayerOf 退回 8, 即不踢。
    // · 只踢不加: 换回大容量地图也不会自动把 AI 加回来, 要加回由玩家自己点槽位 (op 89)。
    // · 开局的 op 112 名册读的是 Room.slots 的 hasSeat(), 这里改完状态后开局自然只发
    //   上限内的槽位, 不会再出现「4 人图塞 8 个 bomber / 出生点 4..7 不存在」。
    // ========================================================================

    /**
     * 把房间人数裁剪到地图上限以内, 并向房间内所有客户端下发踢人同步包。
     * 只做减法、幂等: 同一张图重复选不会重复踢。
     *
     * @param broadcast false = 只改服务端状态 (开局兜底 / 纯状态测试)
     * @return 被踢出的槽号 (从大到小)
     */
    fun applyMapPlayerLimit(mapId: Int, broadcast: Boolean = true): List<Int> {
        val limit = MapCatalog.maxPlayerOf(mapId).coerceIn(1, MAX_SESSION_SLOTS)
        val seated = slots.indices.filter { slots[it].hasSeat() }
        // 只踢「空的 AI 槽」; 从大槽号开始, 留下的槽号连续, 与真端房间 UI 顺序一致。
        val aiSlots = slots.indices.filter { slots[it].isAI && !slots[it].isHuman }
        val kicked = aiSlots.sortedDescending().take((seated.size - limit).coerceAtLeast(0))

        val packets = mutableListOf<PKT>()
        for (slotIdx in kicked) {
            val slot = slots[slotIdx]
            slot.isAI = false
            slot.clearHuman()
            slot.state = FLAG_CLOSED
            packets += PracticeSlotReady(slot = slotIdx, state = 1)
            packets += PracticeSetSlotAI(slot = slotIdx, isAI = false)
        }

        if (broadcast && packets.isNotEmpty()) BC(*packets.toTypedArray())
        return kicked
    }

    /**
     * 进入战斗：销毁可能残留的旧战斗，按当前房间槽位与 [battleConfig] 创建新的 [BattleSession] 并启动主循环。
     *
     * 参战名单与配置都在这一刻定格 ([BattleSession] 持有副本)：上一局的炸弹/推箱/去重表/结算标志
     * 全部随旧会话丢弃，重开一局不会把残留的泡带进新局。
     */
    fun startGame(): BattleSession {
        val newBattle = synchronized(battleLock) {
            battle?.destroy()
            val initialSlots = Array(MAX_SESSION_SLOTS) { idx ->
                val s = slots[idx]
                BattleSlotState(slotId = idx, isHuman = s.isHuman, isAI = s.isAI)
            }
            BattleSession(
                executor = executor,
                config = battleConfig.copy(),
                initialSlots = initialSlots,
                broadcastAll = { packets -> BC(*packets) },
                clientsProvider = { uniqueClients },
                resultSlotsProvider = { resultSlotSnapshots() },
                onMatchFinished = { outcome, expGained, lucciGained ->
                    settleMatch(outcome, expGained, lucciGained)
                },
                onReturnToRoom = { session -> backToRoomFromBattle(session) },
            ).also {
                battle = it
            }
        }
        // EN: 对局推进完全由客户端本地 AI (CAIPlayer / CAIGoal*) 与本地时钟驱动,
        // 服务端只在 C→S op 106 时回 S→C op 139、2.5s 后回 op 138 引爆, 无需任何周期广播。
        // (曾误加每 400ms 的 op 172 "ItemThrow" 广播, 实为地图事件时间轴同步, 见 MapEventSync.kt)
        newBattle.start()
        return newBattle
    }

    private fun backToRoomFromBattle(session: BattleSession): Boolean {
        synchronized(battleLock) {
            if (battle !== session) return false
        }
        return backToRoom(sendRoomChain = session.config.reenterRoomAfterBack)
    }

    /**
     * 下发 S→C op 155 [BackToRoom] 并销毁当前 [BattleSession]（唯一能把客户端从游戏阶段拉回房间的出口）。
     *
     * 时序参照真实服务器「结果窗显示几秒后自动返回」: 结算包之后
     * [BattleConfig.resultReturnDelayMs] 再发（见 [BattleSession] 的结算调度）。
     *
     * ⚠️ 光有 155 只能把客户端「切回 stage 12」，房间对象/会话是空的（实机验证
     * 能回房、AI 还在，但「开始」按钮退化成「准备」= 房主位丢了）。
     * 所以默认再补推整条房间链（[sendRoomEntryChain]，与 C→S op 75 的回应同一串），
     * 把房主/座位/房间名/选图重新灌一遍；[BattleConfig.reenterRoomAfterBack] 置 false 可只发 155。
     *
     * 调试入口: 直接调用 `backToRoom(force = true)` —— 可强制重发、可指定槽位状态字节、可只发 155。
     *
     * @param sendRoomChain 是否在 155 之后补推房间链
     * @param slotStates    槽 0..2 的状态字节（null = 用 [slots] 的真实 state，槽 0 保住房主位）
     * @param chState       op155 首个 E1（`0x687393` → player+0x1AC，默认 0）
     * @param force         true = 忽略「本局已发过 / 当前无战斗」的去重（调试重试用）
     * @return 是否真的发出了包
     */
    fun backToRoom(
        sendRoomChain: Boolean = battleConfig.reenterRoomAfterBack,
        slotStates: List<Int>? = null,
        chState: Int = 0,
        force: Boolean = false
    ): Boolean {
        synchronized(battleLock) {
            val current = battle
            if (current != null) {
                if (!current.claimBackToRoom(force)) return false
                current.destroy()
                battle = null
            } else if (!force) {
                return false
            }
        }

        val states = slotStates ?: (0 until 3).map { slots.getOrNull(it)?.state?.toInt() ?: 0 }
        BC(BackToRoom(chState = chState, slotStates = states))
        // op 155 让客户端切回 stage 12; 同步服务端舞台状态, 之后从房间进商城才能正确返回房间
        uniqueClients.forEach { it.stage = ClientStage.Room.value }
        Logger.info(
            "[Room] 下发 op155 BackToRoom（回房间, stage 12）| chState=$chState " +
                "slotStates=$states roomChain=$sendRoomChain"
        )

        if (sendRoomChain) {
            uniqueClients.forEach {
                sendRoomEntryChain(it, init = false)
                val lucci = it.player?.lucci
                if (lucci != null) {
                    it.send(UserDynamicInfoValue(0, lucci))
                }
            }
            Logger.info("[Room] op155 之后补推房间链sendRoomEntryChain")
        }
        return true
    }

    fun BC(vararg packets: PKT) {
        uniqueClients.forEach { client ->
            packets.forEach { client.ctx.write(it) }
            client.ctx.flush()
        }
    }

    infix fun BC(packet: PKT) {
        uniqueClients.forEach { client ->
            client.ctx.writeAndFlush(packet)
        }
    }

    fun encodeSlots(c: ClientSocket) {
        slots.forEachIndexed { index, slot ->
            val player = slot.player
            if (player != null) {
                c send SetPlayer(index, slot, player)
            } else {
                c send SetSlotState(index, slot)
            }
        }
    }

    /**
     * 对局结束写回所有参战人类玩家的战绩、金币、经验与等级，并返回主玩家的进度前后快照。
     */
    internal fun settleMatch(
        outcome: BattleSession.MatchOutcome,
        expGained: Int,
        lucciGained: Int,
    ): BattleSession.MatchSettlement {
        val won = outcome == BattleSession.MatchOutcome.HUMAN_WIN
        val draw = outcome == BattleSession.MatchOutcome.DRAW
        val distinctPlayers = slots.mapNotNull { it.player }.distinctBy { it.userId }
        if (distinctPlayers.isEmpty()) {
            return BattleSession.MatchSettlement(expBefore = 0, expAfter = expGained, lucciGained = lucciGained)
        }
        val primaryId = slots[0].playerId ?: distinctPlayers.first().userId
        var primaryExpBefore = 0
        var primaryExpAfter = expGained
        for (liveProfile in distinctPlayers) {
            val expBefore = liveProfile.exp.coerceIn(0, 999)
            val settled = PlayerService.settleMatch(
                profile = liveProfile,
                won = won,
                draw = draw,
                expGained = expGained,
                lucciGained = lucciGained,
            )
            if (settled.userId == primaryId) {
                primaryExpBefore = expBefore
                primaryExpAfter = settled.exp.coerceIn(0, 999)
            }
        }
        return BattleSession.MatchSettlement(
            expBefore = primaryExpBefore,
            expAfter = primaryExpAfter,
            lucciGained = lucciGained,
        )
    }

    fun resultSlotSnapshots(): List<GameResultSlotSnapshot> = slots.map { slot ->
        val p = slot.player
        when {
            p != null -> GameResultSlotSnapshot.Human(
                level = p.level,
                win = p.win,
                lose = p.lose,
                draw = p.draw,
            )
            slot.isAI -> GameResultSlotSnapshot.Ai(aiLevel = slot.aILevel)
            else -> GameResultSlotSnapshot.Empty
        }
    }

    private fun Slot.hasSeat(): Boolean = isHuman || isAI

    /** 销毁当前战斗会话（建新房或测试清理时调用）。 */
    fun reset() {
        synchronized(battleLock) {
            battle?.destroy()
            battle = null
        }
    }
}
