package bnbemu.server

import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.debugMode
import bnbemu.netty.ClientSocket
import bnbemu.netty.PKT
import bnbemu.packet.battle.Airplane
import bnbemu.packet.battle.BOMBER_STATE_FREE
import bnbemu.packet.battle.BOMBER_STATE_SAVED
import bnbemu.packet.battle.BOMBER_STATE_TRAPPED
import bnbemu.packet.battle.BombKickThrow
import bnbemu.packet.battle.BomberDeathList
import bnbemu.packet.battle.BomberDie
import bnbemu.packet.battle.DrillResult
import bnbemu.packet.battle.GameResult
import bnbemu.packet.battle.GameResultSlotSnapshot
import bnbemu.packet.battle.InitGameResult
import bnbemu.packet.battle.MovableBoxMove
import bnbemu.packet.battle.ReviveBomber
import bnbemu.packet.battle.SetBombState
import bnbemu.packet.battle.SetBomberState
import bnbemu.packet.battle.WINNER_TEAM_AI
import bnbemu.packet.battle.WINNER_TEAM_DRAW
import bnbemu.packet.battle.WINNER_TEAM_HUMAN
import io.netty.channel.EventLoop
import io.netty.util.concurrent.ScheduledFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 槽位在本局内的生命状态。
 * ⚠️ 与 [Slot.state]（meta[0] 的房间旗标 FLAG_CLOSED / CHIEF / READY）无关，那个是房间座位状态。
 */
enum class SlotMatchState {
    /** 本局不参战（空槽）：不参与胜负统计，也不接受阵亡上报 */
    OUT_OF_MATCH,

    /** 参战且存活 */
    ALIVE,

    /** 参战且已阵亡（阵亡已上报；同一槽位后续的重复上报忽略） */
    DEAD
}

/**
 * 单局战斗内单个槽位的运行时状态。
 * 开局从 [Room.slots] 拍下参战快照，随 [BattleSession] 销毁而释放。
 */
data class BattleSlotState(
    val slotId: Int,
    val isHuman: Boolean,
    val isAI: Boolean,
    var gridX: Int = 0,
    var gridY: Int = 0,
    var matchState: SlotMatchState = if (isHuman || isAI) SlotMatchState.ALIVE else SlotMatchState.OUT_OF_MATCH,
) {
    val hasSeat: Boolean get() = isHuman || isAI
    val isAlive: Boolean get() = matchState == SlotMatchState.ALIVE
    val isInMatch: Boolean get() = matchState != SlotMatchState.OUT_OF_MATCH
}

/**
 * 单局战斗会话（对局生命周期管理：进入战斗创建、回到房间销毁）。
 *
 * 维护局内所有战斗状态与定时任务：
 * - [onTick] 主循环（水泡倒计时、对局超时检测）
 * - 水泡注册 / 移动（op 107 → op 141）/ 飞镖命中引爆（op 108 → op 138）/ 发射泡延时激活
 * - 推箱子去重（op 109 → op 142）、电钻（op 124 → op 156）、飞机道具冷却
 * - 参战槽位生命状态、死亡上报（op 154 action=5）、只剩一队（op 112 action=0x60）与胜负结算（op 169/150 → op 155）
 */
class BattleSession(
    private val executor: EventLoop,
    val config: BattleConfig = BattleConfig(),
    initialSlots: Array<BattleSlotState> = Array(MAX_SESSION_SLOTS) { BattleSlotState(it, isHuman = false, isAI = false) },
    private val broadcastAll: (Array<out PKT>) -> Unit = {},
    private val clientsProvider: () -> Sequence<ClientSocket> = { emptySequence() },
    private val resultSlotsProvider: () -> List<GameResultSlotSnapshot>,
    private val onMatchFinished: (MatchOutcome, Int, Int) -> MatchSettlement? = { _, exp, lucci ->
        MatchSettlement(expBefore = 0, expAfter = exp, lucciGained = lucci)
    },
    private val onReturnToRoom: (BattleSession) -> Boolean = { false },
) {
    /** 对局结算写回档案后的进度与收益快照，用于组装 S→C op 150 GameResult。 */
    data class MatchSettlement(
        val expBefore: Int = 0,
        val expAfter: Int = 0,
        val lucciGained: Int = 0,
    )

    enum class Phase {
        CREATED,
        RUNNING,
        RESULT_DISPATCHED,
        DESTROYED,
    }

    enum class MatchOutcome(val winnerTeam: Int) {
        HUMAN_WIN(WINNER_TEAM_HUMAN),
        AI_WIN(WINNER_TEAM_AI),
        DRAW(WINNER_TEAM_DRAW),
    }

    private enum class MatchEndTrigger {
        DEATH_REPORT,
        LAST_TEAM_STANDING,
        TIMEOUT,
    }

    // 可移动箱子 (CGOTileMovableBox) 的最新推动状态。
    // 来源: C→S op 109 PushMovableBoxReq, 消费后必须广播 S→C op 142 MovableBoxMove。
    // 客户端 (CA.exe 0x0054CABA) 只做格子校验 + 入队, 没有任何本地位移预测,
    // 箱子位移的唯一实现 0x565CE0 只有一个调用者 —— S→C op 142 的 handler 0x0054A5F9。
    // 所以「不回包」会让箱子在任何客户端都不动。
    data class MovableBoxState(
        var gridX: Int,
        var gridY: Int,
        var targetX: Int,
        var targetY: Int,
        var slotId: Int
    )

    // ---- C→S op 107 水泡移动 (抛掷/踢出) → S→C op 141 BombIgnite 回显 (2026-11-09 定案) ----
    //
    // 客户端发包链只做滑行 trace + 入队 (0x0054CCBC / 0x0046E6D7), 没有任何本地位移预测;
    // 水泡位移的唯一驱动是 S→C op 141 的 action=kind 分支 (0x54BD14 -> 0x4598AB 滑行 /
    // 0x459935 抛掷落地), 两个函数全 .text 只有 op141 处理器与 op139 确认链两个调用者。
    // 所以服务端必须把 op 107 前 7 字节 (kind|slot, bombId, from, to) 以 op 141 原样广播,
    // 含踢泡者本人 (他也靠这一包看到球飞)。不要回 op 107 —— EN 局内分发器没有 0x6B 分支。
    //
    // 与 op 109 推箱子同理做去重: 踢/推是 CGameMsgCache 待确认队列 + 每帧 flush 机制,
    // 同一动作可能在帧级重复上报; 而 0x4598AB 会把目标格写进 bomb+0x9C/0xA8 并置 moving,
    // 重复广播同一动作虽无致命后果 (精灵会重新滑向同格), 但会抖动动画与 moving 状态。
    // 窗口 [BattleConfig.bombMoveDedupeWindowMs] 取 400ms: 远大于帧级重发周期,
    // 又短于玩家把球踢回同一位置的连击间隔。
    data class BombMoveState(
        val kind: Int,
        val bombId: Int,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int
    )

    data class DartDetonation(
        val bombId: Int,
        val targetObjectId: Int,
        val gridX: Int,
        val gridY: Int,
        val delayMs: Int,
        val includeSender: Boolean
    )

    private val matchLock = Any()

    @Volatile
    var phase: Phase = Phase.CREATED
        private set

    val isDestroyed: Boolean
        get() = phase == Phase.DESTROYED

    val slotStates: Array<BattleSlotState> = Array(MAX_SESSION_SLOTS) { idx ->
        initialSlots.getOrNull(idx)?.copy() ?: BattleSlotState(idx, isHuman = false, isAI = false)
    }

    val activeSlotCount: Int
        get() = slotStates.count { it.hasSeat }

    private var nextBombId = 0
    val bombs = mutableListOf<Bomb>()
    var lastAirplaneTime = 0L

    private val movableBoxes = ConcurrentHashMap<Int, MovableBoxState>()
    private val movableBoxSentAt = ConcurrentHashMap<Int, Long>()
    private val bombMoves = ConcurrentHashMap<Int, BombMoveState>()
    private val bombMoveSentAt = ConcurrentHashMap<Int, Long>()
    private val dartDetonations = ConcurrentHashMap<Int, DartDetonation>()
    private val dartDetonationFutures: MutableSet<ScheduledFuture<*>> = ConcurrentHashMap.newKeySet()
    private val launchedBombActivations = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    @Volatile
    private var matchStartTimeMs = 0L

    /** 是否已经触发结算调度 (防止重复调度) */
    @Volatile
    var gameOverSent = false
        private set

    /** 是否已经实际发出 op 169/150 (在 gameOverDelayMs 缓冲期内为 false, 供同灭升级平局判断) */
    @Volatile
    private var resultDispatched = false

    @Volatile
    private var pendingOutcome: MatchOutcome? = null

    @Volatile
    private var pendingTrigger: MatchEndTrigger? = null

    @Volatile
    private var backSent = false

    private var tickFuture: ScheduledFuture<*>? = null
    private var gameOverFuture: ScheduledFuture<*>? = null
    private var backToRoomFuture: ScheduledFuture<*>? = null

    fun slotState(slotId: Int): BattleSlotState? = slotStates.getOrNull(slotId)

    fun updateSlotGrid(slotId: Int, gridX: Int, gridY: Int) {
        if (isDestroyed) return
        slotStates.getOrNull(slotId)?.let {
            it.gridX = gridX
            it.gridY = gridY
        }
    }

    fun broadcast(vararg packets: PKT) {
        if (isDestroyed || packets.isEmpty()) return
        broadcastAll(packets)
    }

    /** 启动对局主循环 (tick 定时任务与开局计时)。 */
    fun start() {
        synchronized(matchLock) {
            if (phase == Phase.DESTROYED) return
            tickFuture?.cancel(false)
            gameOverFuture?.cancel(false)
            backToRoomFuture?.cancel(false)
            gameOverFuture = null
            backToRoomFuture = null

            // 每局开局把槽位生命状态重置成 (座位上有人 ? ALIVE : OUT_OF_MATCH) 并清结算标志。
            //
            // 参战槽位 (座位上有人) 置 ALIVE，空槽置 OUT_OF_MATCH —— 保留"空槽"这个第三态，
            // 而不是简单把所有槽位都置 ALIVE，否则 [onBomberDie] 的守卫挡不住
            // "给根本没有 bomber 的槽位广播 op 159" 这类错位/迟到上报。
            //
            // 这里同时把"谁参战"定格成一局开始时的快照：对局中途有人离开也不会把他从结算名单里
            // 抹掉（人是这一局的参战者，离开只是掉线）。
            //
            // ⚠️ 将来若支持"中途入座"，新玩家入座时必须把该槽位置回 [SlotMatchState.ALIVE]，
            //    否则他的阵亡上报会被当成"不参战/重复上报"吞掉，该玩家阵亡后对局永远不会结束。
            gameOverSent = false
            resultDispatched = false
            pendingOutcome = null
            pendingTrigger = null
            backSent = false
            slotStates.forEach {
                it.matchState = if (it.hasSeat) SlotMatchState.ALIVE else SlotMatchState.OUT_OF_MATCH
            }
            phase = Phase.RUNNING
            matchStartTimeMs = System.currentTimeMillis()
            val period = config.tickIntervalMs.coerceAtLeast(1L)
            tickFuture = executor.scheduleAtFixedRate(::onTick, 0, period, TimeUnit.MILLISECONDS)
        }
    }

    // CGameSession::Update - 定时更新水泡倒计时并广播爆炸状态；超过对局时限 (默认 3 分钟) 自动判平局结束
    // 注意: S→C op 138 SetBombState(bombId, 0) (CA.exe 0x005481BD -> 0x00534B18 -> 0x00565E9F)
    // 已完整处理水泡引爆、十字水柱、炸箱子与爆出道具。
    // 绝不可在此追加发送 S→C op 148 (CA.exe 0x0054BA1A -> 0x00477CEF), 因为 op 148 实为
    // DropObstacleResult (放置地面障碍物: 1=地雷, 2=陷阱, 3=香蕉皮 Item/BananaGround, 4=图钉 Item/PushpinGround)!
    private fun onTick() {
        if (isDestroyed) return
        val now = System.currentTimeMillis()
        val timedOut = synchronized(matchLock) {
            if (phase != Phase.RUNNING) return
            !gameOverSent && config.matchTimeoutMs > 0L && matchStartTimeMs > 0L &&
                (now - matchStartTimeMs >= config.matchTimeoutMs)
        }
        if (timedOut) {
            onMatchTimeout()
            return
        }

        val stateChanged = mutableListOf<Bomb>()
        synchronized(bombs) {
            if (isDestroyed) return
            bombs.removeIf { bomb ->
                if (bomb.updateState(now)) {
                    stateChanged.add(bomb)
                }
                bomb.shouldRemove()
            }
        }

        if (stateChanged.isNotEmpty() && !isDestroyed) {
            stateChanged.forEach { bomb ->
                broadcast(SetBombState(bomb.id, bomb.state))
            }
        }
    }

    // 客户端上报槽位事件 (Opcode 149) 时触发
    fun onEventItem(slotId: Int, token: Int, action: Int, param: Int) {
        if (isDestroyed) return
    }

    // CGameSession::OnRequestAirplane
    fun onRequestAirplane(items: List<AirplaneItem>): Boolean {
        if (isDestroyed) return false
        val now = System.currentTimeMillis()
        if (lastAirplaneTime + 7000 > now) return false
        val scheduledTime = now + 2000
        lastAirplaneTime = scheduledTime
        broadcast(Airplane(scheduledTime.toInt(), items))
        return true
    }

    // 完全相同的推箱动作 (同 boxSeq + 同 src/dst) 在 [BattleConfig.movableBoxDedupeWindowMs] 窗口内只广播一次。
    //
    // 去重是必须的: 客户端 0x0054CABA 之后进入 0x5393C2 的待确认队列, OnTick (0x0053A700)
    // 每帧检查、重试计数 > 5 就把**同一个动作**再发一次。而客户端 0x565CE0 会先校验
    // "箱子此刻仍在 (gridX,gridY)" —— 同一个 src→dst 广播第二次必然校验失败, 各客户端会
    // 触发 0x549C35 往服务端回 C→S op 149 回滚上报。
    // 但窗口必须有限: 万一一端的箱子因为漏包/校验失败没动, 玩家再推一次时必须能重播自愈,
    // 否则那个箱子会永久卡死。窗口取 400ms, 远大于一次重发周期(帧级), 又短于人的连点间隔。
    // 箱子被推回去再推回来时 src/dst 都不同, 不会被误判成重复。
    // 返回是否真的广播了 (重复上报不广播, 便于测试/调试)。
    fun onMovableBoxPush(
        boxSeq: Int,
        gridX: Int,
        gridY: Int,
        targetX: Int,
        targetY: Int,
        slotId: Int
    ): Boolean {
        if (isDestroyed) return false
        // boxSeq/末尾字节都是 E1/E2 原样字段, 只做宽度兜底, 不做语义过滤:
        // 末尾字节是客户端 CGameMsgCache 待确认队列下标, 收到的这一端 (0x565CE0 第 6 参数) 并不使用,
        // 过滤掉反而可能让某些槽位的推箱永远不生效。
        if (boxSeq !in 0..0xFFFF || slotId !in 0..0xFF) return false

        val now = System.currentTimeMillis()
        val next = MovableBoxState(gridX, gridY, targetX, targetY, slotId)
        val prev = movableBoxes[boxSeq]
        if (prev == next && now - (movableBoxSentAt[boxSeq] ?: 0L) < config.movableBoxDedupeWindowMs) {
            return false
        }
        movableBoxes[boxSeq] = next
        movableBoxSentAt[boxSeq] = now

        // 广播给所有客户端(含推箱者本人): 推箱者自己也需要这一包才会看到箱子动
        broadcast(MovableBoxMove(boxSeq, gridX, gridY, targetX, targetY, slotId))
        return true
    }

    fun movableBoxState(boxSeq: Int): MovableBoxState? = movableBoxes[boxSeq]

    // ---- C→S op 124 电钻 (Drill) → S→C op 156 DrillResult 回显 ----
    // 客户端用道具「电钻」时 服务端必须把这条请求**原样回放给所有人, 含使用者本人**, 否则谁也不会看到方块消失。
    //
    // 服务端没有方块/地图状态模型 (消块在各客户端本地确定性重算), 这里只做坐标与槽位的健全性校验:
    //   · 槽位进 packet 的高 4 位, 越界 (>=8) 会让客户端 0x533D5C 取到野 bomber, 必须丢;
    //   · 坐标越界时客户端 0x55EAC3 会整包丢弃, 发了也是白发, 直接不发。
    // 不做去重: op 124 是按键一次发一次 (直发, 不走 CGameMsgCache 待确认队列), 不存在帧级重发。
    fun onDrill(gridX: Int, gridY: Int, slotId: Int, action: Int): Boolean {
        if (isDestroyed) return false
        if (slotId !in slotStates.indices) {
            if (debugMode) println("[BattleSession] op124 电钻: 非法槽位 slot=$slotId, 丢弃")
            return false
        }
        if (gridX !in 0..12 || gridY !in 0..14) {
            if (debugMode) println("[BattleSession] op124 电钻: 坐标越界 ($gridX,$gridY), 丢弃")
            return false
        }

        broadcast(DrillResult(gridX, gridY, slotId, action))
        if (debugMode) {
            println(
                "[BattleSession] 电钻 slot=$slotId grid=($gridX,$gridY) dir=$action" +
                    " | 已广播 S→C op156"
            )
        }
        return true
    }

    // 返回是否真的广播了 (重复上报不广播, 便于测试/调试)。
    fun onBombMove(
        slotId: Int,
        kind: Int,
        bombId: Int,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        moveDuration: Int
    ): Boolean {
        if (isDestroyed) return false
        val now = System.currentTimeMillis()
        val next = BombMoveState(kind, bombId, fromX, fromY, toX, toY)
        val prev = bombMoves[bombId]
        if (prev == next && now - (bombMoveSentAt[bombId] ?: 0L) < config.bombMoveDedupeWindowMs) {
            return false
        }
        bombMoves[bombId] = next
        bombMoveSentAt[bombId] = now

        // 更新服务端水泡状态: 滑行期间暂停倒计时, 到点后由 Bomb.updateState 续 2s 再引爆
        synchronized(bombs) {
            bombs.find { it.id == bombId }?.apply {
                gridX = toX
                gridY = toY
                isMoving = true
                expireAt = now + moveDuration.coerceIn(0, 10_000)
            }
        }

        // 回显 = op 107 前 7 字节原样 (action = kind, 4..7 滑行 / 0..3 抛掷)。
        // oldX/oldY 传 from: 客户端 0x54BDEA 只用 oldX 与 bomb+0x9C 做校验, 不符也照常执行。
        broadcast(
            BombKickThrow(
                slotId = slotId,
                bombId = bombId,
                x = toX,
                y = toY,
                oldX = fromX,
                oldY = fromY,
                action = kind
            )
        )
        return true
    }

    fun bombMoveState(bombId: Int): BombMoveState? = bombMoves[bombId]

    // ---- C→S op 108 对象命中上报 → S→C op138 SetBombState(state=0) 引爆  ----
    //
    // 飞镖 (CGODart) 命中水泡时客户端只发 op121 (发射) + op108 (命中预告), 全函数没有
    // sub_565E9F —— **不会本地引爆**, 必须等服务端下令:
    //   S→C op138 0x005481BD → 0x00534B18 → 0x00534338 (按 bombId 找水泡) → 0x00565E9F 引爆。
    // op108 的 delayMs = 飞镖飞行时间 (1200*射程px/1000 - 2*ping), 服务端按它延时广播,
    // 让飞行动画先播完。objId 是客户端对象混淆 ID (sub_44B67A(obj+144)), 不能当 bombId,
    // 所以只能按 gridX/gridY 在 [bombs] 里反查服务端自己的 id (即 op139 回填的那个值)。
    // 延时上限见 [BattleConfig.dartDetonationMaxDelayMs]。
    //
    // · 命中格没有登记水泡 → 不处理 (op108 也可能命中箱子/角色/地面障碍)。
    // · 找到后立刻从 bombs 移除: 既对同一颗泡的重复上报去重 (实测连发两镖会收到两条
    //   完全相同的 op108), 也防止 onTick 2.5s 到期再广播一次引爆。
    // · delayMs > 0 = 飞镖预告路径, 客户端没炸过 → 广播含发射者本人。
    //   delayMs == 0 = 客户端已本地引爆的路径 (0x0046DFAD / C2S_Op108_And_Op154_Action1),
    //   只同步其它客户端, 不给发射者重复发引爆包。
    /**
     * @return 是否真的引爆了一颗水泡 (重复上报 / 命中格无泡都返回 false)。
     * @param broadcast false = 只改服务端状态, 供单元测试使用, 不排程广播。
     */
    fun onObjectHit(
        targetObjectId: Int,
        gridX: Int,
        gridY: Int,
        delayMs: Int,
        sender: ClientSocket? = null,
        broadcast: Boolean = true
    ): Boolean {
        if (isDestroyed) return false
        if (gridX !in 0..12 || gridY !in 0..14) return false

        val bomb = synchronized(bombs) {
            val hit = bombs.firstOrNull { it.gridX == gridX && it.gridY == gridY && it.state != 0 }
            if (hit != null) bombs.remove(hit)
            hit
        }
        if (bomb == null) {
            if (debugMode) {
                val known = synchronized(bombs) {
                    bombs.joinToString { "id=${it.id}@(${it.gridX},${it.gridY})" }
                }
                println(
                    "[BattleSession] op108 hit -> no bomb at ($gridX,$gridY) objId=$targetObjectId " +
                        "delay=${delayMs}ms bombs=[$known]"
                )
            }
            return false
        }

        val includeSender = delayMs > 0
        val delay = delayMs.toLong().coerceIn(0, config.dartDetonationMaxDelayMs)
        dartDetonations[bomb.id] = DartDetonation(
            bomb.id, targetObjectId, gridX, gridY, delayMs, includeSender
        )

        if (debugMode) {
            println(
                "[BattleSession] op108 hit -> detonate bomb=${bomb.id} grid=($gridX,$gridY) " +
                    "objId=$targetObjectId delay=${delay}ms includeSender=$includeSender"
            )
        }

        if (broadcast) {
            val packet = SetBombState(bomb.id, state = 0)
            val sendFn = {
                if (!isDestroyed) {
                    clientsProvider()
                        .filter { includeSender || it !== sender }
                        .forEach { it.ctx.writeAndFlush(packet) }
                }
            }
            if (delay == 0L) {
                sendFn()
            } else {
                var scheduled: ScheduledFuture<*>? = null
                scheduled = executor.schedule({
                    scheduled?.let { dartDetonationFutures.remove(it) }
                    sendFn()
                }, delay, TimeUnit.MILLISECONDS)
                dartDetonationFutures.add(scheduled)
            }
        }
        return true
    }

    fun dartDetonationState(bombId: Int): DartDetonation? = dartDetonations[bombId]

    // 角色死亡上报处理 (C→S op 154 action=5 -> CA.exe 0x0046DB1D CGOGameBomber::OnDie)
    //
    //   这条上报只对**真人玩家**成立 —— 0x46DB01 处先调 0x45FBD3,
    //   其判据是 `0 <= bomber+0x4D0 < 2`, 而 `+0x4D0` 是调试串 0x892838 "m_selfidx = " 所指的
    //   **网络玩家序号** (相邻的 +0x4DC 是 "m_faislot", AI 槽号)。AI 的 m_selfidx 恒为 -1
    //   (bomber 初始化 0x4680xx 置 -1, 只有真人在 0x639FE3 循环里经 0x45FDDD 赋 0/1),
    //   所以**杀光 AI 时一个 action=5 都不会来**。整段实战日志 (1510 行 / 72 个 op154) 里
    //   action 只有 4 / 1 / 0, 没有 2 / 3 / 5, 也印证了这点。
    //   → 「玩家赢」必须靠 [onLastTeamStanding] (op112 action=0x60); 这里保留 action=5 是为了
    //     处理玩家自己阵亡 (以及将来真正的多真人房)。
    //
    // 结算判定规则
    //   客户端只是把每个阵亡的 bomber 转发给服务端, 自身没有任何"敌人清空"判定, 全部依赖服务端下令。
    //   于是「杀光全部 AI」之后对局永远不会结束。现在按 [BattleSlotState.matchState] 统计双方存活人数,
    //   任意一方全灭即结束对局:
    //     · 双方同时全灭            → 平局 (DRAW, winnerTeam = 0xFF)
    //     · 人类全灭 && AI 存活     → AI 胜 (winnerTeam = 1, 胜利者取一个仍活着的 AI 槽位)
    //     · AI 全灭 && 人类存活     → 人类胜 (winnerTeam = 0, 胜利者取玩家槽位)
    //   结算包延迟 [BattleConfig.gameOverDelayMs] 下发: 客户端要先播完阵亡动画;
    //   在缓冲期内若另一方也全灭则升级为平局。
    //   顺序 op 169 InitGameResult → op 150 GameResult（缺 169/150 不弹结算窗）。
    fun onBomberDie(slotId: Int) {
        if (slotId !in slotStates.indices) return
        val slot = slotStates[slotId]
        synchronized(matchLock) {
            if (phase != Phase.RUNNING || resultDispatched) return
            // 只有"参战且存活"的槽位才接受阵亡上报:
            //   · DEAD         → 重复上报 (CGameMsgCache 重发 / 抓包残流 / 连续两次 OnDie), 忽略
            //   · OUT_OF_MATCH → 空槽的错位/迟到上报, 忽略, 绝不能给没有 bomber 的槽位发 op 159
            if (slot.matchState != SlotMatchState.ALIVE) return
            slot.matchState = SlotMatchState.DEAD
        }

        // 阵亡广播 = 两条包配合:
        //   1) S→C op159 BomberDie(slot) —— 客户端 0x0054C378: 置状态 17、清队伍桶、
        //      播 0x37（实测这声听不见，所以**光发它等于没声音**）；
        //   2) S→C op146 state=5「死亡名单」—— 0x54E0B8: 把该 bomber 从场上摘除 + 刷 UI，
        //      结尾播 0x2A 死亡音。
        //   顺序必须先 159 再名单：名单会把 bomber 摘掉，之后再发 159 会因 `[bomber+8]==0` 提前返回。
        //   名单的通用头 slot 不能写死 0：客户端 0x54DDD1 会先按它取 bomber，空槽/已摘除
        //   会让整个包被跳过（连 0x2A 都不播）。用本次名单里第一个槽——判空发生在摘除之前。
        broadcast(BomberDie(slotId))
        broadcast(BomberDeathList(listOf(slotId), headerSlot = slotId))
        println(
            "[BattleSession] BomberDie slot=$slotId (" +
                (if (slot.isHuman) "人类" else if (slot.isAI) "AI" else "空槽") +
                ") | 存活人类=${slotStates.indices.filter { slotStates[it].isHuman && slotStates[it].isAlive }} " +
                "存活AI=${slotStates.indices.filter { slotStates[it].isAI && slotStates[it].isAlive }}"
        )

        finishMatchIfDecided()
    }

    // 客户端"只剩一支队伍"上报 (C→S op 112 action=0x60) —— EN 客户端唯一的对局结束信号
    //
    // 逆向 (CA.exe 0x54B380~0x54B450, 帧更新里每帧跑一次):
    //   1) 开一个 9 槽的计数组 [ebp-0x5C] (0x24 = 9*4), 对每个 bomber:
    //        if (bomber->vtable[0xAC]() != 0)  ++cnt[bomber->+0x84]      // +0x84 = 队伍号 (0..8)
    //   2) 统计非空队伍的个数 n; 若 n > 1 直接 ret (还有两队以上, 对局继续)
    //   3) n == 1 时, 对每个 bomber:
    //        if (bomber->+0x8 != 0 && bomber->vtable[0x150]() == 0)      // 0x45FBA0: 不是空壳
    //            0x54A773(bomber) -> 发 C→S op 112: E1(0x60 | slot) + E2(x) + E2(y)
    //   即:**客户端自己判定"只剩一支队伍", 然后对存活方每帧狂发 action=0x60**, 一直发到服务端
    //   下发结算为止 (实测杀光 AI 后连发数百条, 其它局内包全部停摆 —— 客户端卡在这个等待里)。
    //
    // 所以这个包就是"赢"的唯一信号, 而且双向通用:
    //   · 存活方是真人槽 → 玩家胜 (客户端已本地判死全部 AI, 但永远不会为 AI 发 op154 action=5,
    //     见 docs/agents-notes-battle.md: op154 action=5 有 m_selfidx 闸门, AI 恒不通过)
    //   · 存活方是 AI 槽   → AI 胜 (玩家全灭, 这时通常也已经有 op154 action=5 slot=0 先到)
    //
    // ⚠️ 不要再给落败槽位广播 S→C op 159: 客户端本地阵亡时已经播过死亡动画 (OnDie 是本地函数),
    //    服务端只是"不知道", 再来一条 159 会让死亡动作重播。
    fun onLastTeamStanding(slotId: Int) {
        if (slotId !in slotStates.indices) return
        synchronized(matchLock) {
            if (phase != Phase.RUNNING || gameOverSent) return

            // 单座位房 (1 人无 AI) 客户端从第一帧就会满足"只剩一队", 那不是胜负而是没对手;
            // 真正参战的房间至少要有两个座位 (练习房 = 1 真人 + 1..7 AI)
            if (activeSlotCount < 2) return

            val winner = slotStates[slotId]
            // 空槽上报丢弃: 客户端只会替"真人或 AI"的存活槽位发这个包 (vtable[0x150] == 0 的过滤)
            if (!winner.hasSeat) return

            val humanWin = winner.isHuman
            // 落败方一律补记 DEAD, 让 matchState 与服务端认知一致 (客户端本地早就判死了)
            slotStates.forEach { s ->
                val sameSide = if (humanWin) s.isHuman else s.isAI
                if (s.hasSeat && !sameSide) s.matchState = SlotMatchState.DEAD
            }

            // action=0x60 是每帧重发的, 这里靠 gameOverSent 去重
            emitMatchResultLocked(
                outcome = if (humanWin) MatchOutcome.HUMAN_WIN else MatchOutcome.AI_WIN,
                winnerSlot = slotId,
                trigger = MatchEndTrigger.LAST_TEAM_STANDING
            )
        }
    }

    /** 对局超时（默认 3 分钟 = 180,000ms），与真端 CGameSession::Update 一致直接判平局结束。 */
    fun onMatchTimeout() {
        synchronized(matchLock) {
            if (phase != Phase.RUNNING || gameOverSent) return
            emitMatchResultLocked(
                outcome = MatchOutcome.DRAW,
                winnerSlot = -1,
                trigger = MatchEndTrigger.TIMEOUT,
                detail = "对局超时(${config.matchTimeoutMs}ms)"
            )
        }
    }

    /**
     * 一方全灭或双方同时全灭即结束对局。调用前必须已经把阵亡槽位标记为 [SlotMatchState.DEAD]。
     * 只有[人类]与[AI]两个阵营都还有人时才继续对局; 任一方空出即按存活情况结算（同灭为平局）。
     *
     * 阵营按开局定格的 [BattleSlotState.isHuman] / [BattleSlotState.isAI] 划分,
     * 中途入座/离座不改变本局的参战名单。
     */
    private fun finishMatchIfDecided() {
        synchronized(matchLock) {
            if (phase != Phase.RUNNING || resultDispatched) return

            // 阵营划分: 参战且是真人 = 人类阵营, 参战且 isAI = AI 阵营。不参战的空槽两边都进不去。
            val humanIdx = slotStates.indices.filter { slotStates[it].isHuman }
            val enemyIdx = slotStates.indices.filter { slotStates[it].isAI }
            if (humanIdx.isEmpty()) return          // 没有人类就没有"人类胜负"可言, 交给上层流程

            val humanAlive = humanIdx.filter { slotStates[it].isAlive }
            val enemyAlive = enemyIdx.filter { slotStates[it].isAlive }

            // 结束条件: 人类全灭, 或 AI 全灭 (enemyIdx 为空时不存在"AI 全灭"这种胜负)。
            // 这样即使将来开了纯人类 PvP 房, 死一个人也不会被误判成"敌人清空"。
            val humanWiped = humanAlive.isEmpty()
            val enemyWiped = enemyIdx.isNotEmpty() && enemyAlive.isEmpty()
            if (!humanWiped && !enemyWiped) return   // 双方都还有人, 继续打

            val outcome = when {
                humanWiped && enemyWiped -> MatchOutcome.DRAW
                !humanWiped -> MatchOutcome.HUMAN_WIN
                else -> MatchOutcome.AI_WIN
            }
            val winnerSlot = when (outcome) {
                MatchOutcome.HUMAN_WIN -> humanAlive.first()
                MatchOutcome.AI_WIN -> enemyAlive.first()
                MatchOutcome.DRAW -> -1
            }

            if (!gameOverSent) {
                emitMatchResultLocked(
                    outcome = outcome,
                    winnerSlot = winnerSlot,
                    trigger = MatchEndTrigger.DEATH_REPORT,
                    detail = "存活人类=$humanAlive 存活AI=$enemyAlive"
                )
            } else if (pendingTrigger == MatchEndTrigger.DEATH_REPORT && outcome == MatchOutcome.DRAW) {
                // 在 gameOverDelayMs 动画缓冲期内另一阵营也全灭（同炸弹/同帧双灭），由单方胜负升级为平局
                pendingOutcome = MatchOutcome.DRAW
                println("[BattleSession] 双方同时全灭，结算结果升级为平局 | 存活人类=$humanAlive 存活AI=$enemyAlive")
            }
        }
    }

    /**
     * 真正下发结算包。三条触发路径共用:
     *   · [finishMatchIfDecided]  —— 靠 op154 action=5 逐个记录阵亡后的服务端统计
     *   · [onLastTeamStanding]    —— 靠 op112 action=0x60 客户端自报"只剩一支队伍"
     *   · [onMatchTimeout]        —— 对局达到 3 分钟上限自动判平局
     *
     * 固定顺序 op 169 InitGameResult → op 150 GameResult（客户端 0x55856A → 0x5584CC 建
     * CGameResultDocument，op 150 再填金币/经验并弹窗）。
     */
    private fun emitMatchResultLocked(
        outcome: MatchOutcome,
        winnerSlot: Int,
        trigger: MatchEndTrigger,
        detail: String = ""
    ) {
        if (phase != Phase.RUNNING || gameOverSent) return
        gameOverSent = true
        pendingOutcome = outcome
        pendingTrigger = trigger

        val outcomeLabel = when (outcome) {
            MatchOutcome.HUMAN_WIN -> "玩家胜"
            MatchOutcome.AI_WIN -> "AI 胜"
            MatchOutcome.DRAW -> "平局"
        }
        println(
            "[BattleSession] 对局结束: $outcomeLabel" +
                " | winnerSlot=$winnerSlot" + (if (detail.isEmpty()) "" else " | $detail")
        )

        gameOverFuture = executor.schedule({
            val finalOutcome: MatchOutcome
            synchronized(matchLock) {
                if (phase == Phase.DESTROYED || resultDispatched) return@schedule
                resultDispatched = true
                phase = Phase.RESULT_DISPATCHED
                finalOutcome = pendingOutcome ?: outcome
            }
            val expGained = when (finalOutcome) {
                MatchOutcome.HUMAN_WIN -> config.winExpAfter
                MatchOutcome.AI_WIN -> config.loseExpAfter
                MatchOutcome.DRAW -> config.drawExpAfter
            }
            val lucciGained = when (finalOutcome) {
                MatchOutcome.HUMAN_WIN -> config.winLucci
                MatchOutcome.AI_WIN -> config.loseLucci
                MatchOutcome.DRAW -> config.drawLucci
            }
            val settlement = onMatchFinished(finalOutcome, expGained, lucciGained)
            broadcast(
                InitGameResult(mode = 0, mapId = 0),
                GameResult(
                    winnerTeam = finalOutcome.winnerTeam,
                    delayMs = 2000,
                    expBefore = settlement?.expBefore ?: 0,
                    expAfter = settlement?.expAfter ?: expGained,
                    lucciGained = settlement?.lucciGained ?: lucciGained,
                    slots = resultSlotsProvider(),
                )
            )
            // 结算后停掉对局主循环 (水泡倒计时), 与客户端离开棋盘同步
            stopSimulation()
            // ⚠️ 结果窗弹出 ≠ 对局结束: 客户端没有任何本地超时会把结果窗关掉
            // (op150 的 delayMs 只决定「多久后弹窗」, 见 packet/room/BackToRoom.kt)。
            // 必须由服务端补一条 S→C op 155, 客户端才会拆掉 CGameStage 回到 stage 12 房间。
            // resultReturnDelayMs < 0 = 关闭自动回房 (由测试/调试手动触发)。
            if (config.resultReturnDelayMs >= 0L && !isDestroyed) {
                backToRoomFuture = executor.schedule(
                    { if (!isDestroyed) onReturnToRoom(this) },
                    config.resultReturnDelayMs,
                    TimeUnit.MILLISECONDS
                )
            }
        }, config.gameOverDelayMs, TimeUnit.MILLISECONDS)
    }

    /** 抢占本局回房发送权（避免自动定时回房与手动触发重发）。 */
    internal fun claimBackToRoom(force: Boolean): Boolean = synchronized(matchLock) {
        if (phase == Phase.DESTROYED) return false
        if (backSent && !force) return false
        backSent = true
        true
    }

    /**
     * 调试专用：只广播 S→C op159 [BomberDie]，**不改** `matchState`、**不触发**结算。
     *
     * 用途：验证"op159 到底会不会让客户端播死亡音效 / 进入状态 17"。
     * 对局中在聊天框输入 `!die <slot>` 即可触发（见 handler/ChatMsg.kt，仅 debugMode；
     * 前缀不能是 `/`，`/` 开头的整行会被客户端命令表吃掉，op 18 根本不会发出）。
     */
    fun debugBroadcastBomberDie(slotId: Int) {
        if (isDestroyed || slotId !in slotStates.indices) return
        println("[BattleSession][debug] 手动广播 BomberDie slot=$slotId")
        broadcast(BomberDie(slotId))
    }

    /**
     * 广播 S→C op 146 [SetBomberState]：局内 bomber 事件伞（详见 packet/room/SetBomberState.kt）。
     *
     * 已知 state（全部反汇编 + 实机验证）：
     *   · 2  = **困住**（气泡），必须带目标当前坐标，见 [trapBomber]
     *   · 3  = 被救脱困（需要坐标 + saver），见 [freeBomber]
     *   · 5  = **死亡名单**（官方服务端的同一条消息，见 [broadcastDeathList]）
     *   · 9  = 护盾/暂时无敌；10 = 护盾被打破（音效 0x87）
     *   · 12 = 脱困但不改坐标（角色留在原地）
     *   · 13 = 复活卡特效（对别人使用）
     *
     * 调试入口：聊天框 `st <state> <slot> [x y]`（仅 debugMode）。**没有自动调用点**。
     */
    fun setBomberState(slotId: Int, state: Int, x: Int = 0, y: Int = 0, saverSlot: Int = -1) {
        if (isDestroyed || slotId !in slotStates.indices) return
        if (!slotStates[slotId].hasSeat) return
        broadcast(SetBomberState(slotId, state, x, y, saverSlot))
        println("[BattleSession] SetBomberState slot=$slotId state=$state x=$x y=$y" + if (saverSlot >= 0) " saver=$saverSlot" else "")
    }

    /**
     * 把某个槽位的 bomber 装进泡泡（op146 state 2，客户端 0x54DEE5）。
     *
     * ⚠️ 必须传目标**当前坐标**：state 2 的第一步就是 `vt[0x64](x, y)` 设位置，
     *    传 0,0 会把角色瞬移到地图左上角（实测"发完 trap 人物就不见了"）。
     *    坐标的唯一来源是 C→S op112 上报后由 [updateSlotGrid] 记下的 [BattleSlotState.gridX]/[gridY]
     *    （AI 也有，客户端会替它发）；本函数不会自动取用，调用方必须显式传入。
     *
     * ⚠️ 只发 state 2 就够：实测再补 state 9 只会让目标套上护盾（见 [setBomberState]），
     *    并不会让"撞上去"变成破泡。客户端的破泡分支判的是 `0x45D15B` = `[+0x2AB8] >= 0`，
     *    而 `+0x2AB8` 是**护盾计时**（0x45E923 / 0x45E998），不是被困计时。
     *
     * @param x/y 显式坐标；未知时拒绝下发（宁可不动也不能瞬移）。
     * @return 是否真的发出了包
     */
    fun trapBomber(slotId: Int, x: Int = -1, y: Int = -1): Boolean {
        if (isDestroyed || slotId !in slotStates.indices) return false
        val slot = slotStates[slotId]
        if (!slot.hasSeat) return false
        if (x < 0 || y < 0) {
            println("[BattleSession] 无法困住 slot=$slotId: 没有坐标")
            return false
        }
        broadcast(SetBomberState(slotId, BOMBER_STATE_TRAPPED, x, y))
        println("[BattleSession] 困住 slot=$slotId: state=2 x=$x y=$y")
        return true
    }

    /**
     * 让某个槽位从泡泡里出来。两种 state：
     *   · 12（默认）= 脱困但不改坐标 —— 实测角色留在原地，最安全；
     *   · 3（传了 saverSlot）= "被某人救下"，会在格子上放特效，但**必须带坐标**，否则瞬移。
     *
     * @return 是否真的发出了包
     */
    fun freeBomber(slotId: Int, saverSlot: Int = -1, x: Int = -1, y: Int = -1): Boolean {
        if (isDestroyed || slotId !in slotStates.indices) return false
        val slot = slotStates[slotId]
        if (!slot.hasSeat) return false

        if (saverSlot < 0) {
            // 12 = 脱掉气泡但**不改坐标**（角色留在原地）—— 实测最安全。
            // 不要顺手发 state 10：那是"破盾"，会把 target 的护盾（如果有）一起打掉并播 0x87。
            broadcast(SetBomberState(slotId, BOMBER_STATE_FREE))
            println("[BattleSession] 脱困 slot=$slotId: state=12")
            return true
        }

        if (x < 0 || y < 0) {
            println("[BattleSession] 无法用 state 3 救下 slot=$slotId: 没有坐标, 退回 state 12")
            broadcast(SetBomberState(slotId, BOMBER_STATE_FREE))
            return true
        }
        broadcast(SetBomberState(slotId, BOMBER_STATE_SAVED, x, y, saverSlot))
        println("[BattleSession] SetBomberState slot=$slotId state=${BOMBER_STATE_SAVED}(被救) x=$x y=$y saver=$saverSlot")
        return true
    }

    /**
     * 广播"死亡名单"（op146 state=5）—— 真正用来通知阵亡的消息。
     *
     * 调试入口：聊天框 `!kill <slot>[ <slot>...]`（仅 debugMode）。**没有自动调用点**。
     */
    fun broadcastDeathList(slotIds: List<Int>, debug: Boolean = false) {
        if (isDestroyed) return
        val valid = slotIds.filter { it in slotStates.indices && slotStates[it].hasSeat }
        if (valid.isEmpty()) return
        // 通用头指向名单第一个槽：客户端判空在 case5 摘除之前，此刻它一定还在。
        broadcast(BomberDeathList(valid, headerSlot = valid.first()))
        println("[BattleSession] BomberDeathList slots=$valid" + (if (debug) " (debug)" else ""))
    }

    /**
     * 把某个已阵亡的槽位拉回场上（S→C op 160 [ReviveBomber] 的入口）。
     *
     * 目前**没有任何调用点**：这是给"复活币"类道具预留的基础设施 —— 客户端侧 0x55345D 会
     * 把 bomber 置活、清掉陷阱/水泡并播状态 43 的复活动作，服务端这里同步自己的 `matchState`，
     * 否则下一次 [finishMatchIfDecided] 仍会把这名玩家算作阵亡。
     *
     * 接入复活币时要补的东西（等 C→S 侧的道具使用包实测后再接）：
     *   1. 道具校验（持有量 / 是否局内 / 是否真死），
     *   2. 扣道具 + 下发对应的背包同步包，
     *   3. 决定复活后的位置/保护期（客户端 0x4606C2 会把位置交还给本地状态机）。
     *
     * @return true 表示确实下线了复活的 160 包
     */
    fun reviveBomber(slotId: Int): Boolean {
        if (isDestroyed || slotId !in slotStates.indices) return false
        synchronized(matchLock) {
            if (phase != Phase.RUNNING || gameOverSent) return false   // 已经结算, 复活没有意义
            val slot = slotStates[slotId]
            if (!slot.hasSeat) return false                            // 空槽没有 bomber 可复活
            if (slot.matchState != SlotMatchState.DEAD) return false
            slot.matchState = SlotMatchState.ALIVE
        }
        broadcast(ReviveBomber(slotId))
        println("[BattleSession] ReviveBomber slot=$slotId")
        return true
    }

    fun launchedBombActivationPending(bombId: Int): Boolean = launchedBombActivations.containsKey(bombId)

    /** 排程延时激活; 到点由 [activateLaunchedBomb] 检查水泡是否还活着再决定发不发。 */
    fun scheduleLaunchedBombActivation(bombId: Int) {
        if (isDestroyed) return
        val delay = config.launchedBombActivationDelayMs.coerceAtLeast(0)
        launchedBombActivations[bombId]?.cancel(false)
        launchedBombActivations[bombId] = executor.schedule(
            { activateLaunchedBomb(bombId) },
            delay,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * 落地激活: 下发 S→C op 138 SetBombState(bombId, state=1)。
     * 幂等: 排程记录被消费掉才执行, 重复触发 (或销毁后迟到的任务) 直接跳过。
     * @return 是否真的下发; 水泡已被提前引爆 (飞镖/钉子 [onObjectHit] 或 [onTick] 到期移除) 时跳过并返回 false。
     */
    fun activateLaunchedBomb(bombId: Int): Boolean {
        launchedBombActivations.remove(bombId) ?: return false
        if (isDestroyed) return false
        val stillAlive = synchronized(bombs) {
            bombs.any { it.id == bombId && it.state != 0 }
        }
        if (!stillAlive) {
            if (debugMode) {
                println("[BattleSession] launched bomb $bombId 已被提前引爆, 跳过落地激活")
            }
            return false
        }
        broadcast(SetBombState(bombId, state = 1))
        return true
    }

    fun addBomb(
        slotId: Int,
        gridX: Int,
        gridY: Int,
        isSpecial: Boolean = false,
        unk: Int = 0,
        bombId: Int? = null,
        broadcastInitialState: Boolean = true
    ): Int {
        if (isDestroyed) return -1
        val allocatedId = synchronized(bombs) {
            val id = bombId ?: nextBombId++
            if (id >= nextBombId) {
                nextBombId = id + 1
            }
            id
        }
        executor.execute {
            if (isDestroyed) return@execute
            val bomb = Bomb(allocatedId, slotId, (gridY shl 8) or gridX, 0, isSpecial).apply {
                this.gridX = gridX
                this.gridY = gridY
                this.expireAt = System.currentTimeMillis() + 2500   // 引信 2.5s, 到点由 onTick 广播 state=0 引爆
            }
            synchronized(bombs) {
                if (isDestroyed) return@execute
                bombs.removeIf { it.id == allocatedId }
                bombs += bomb
            }
            if (broadcastInitialState && !isDestroyed) {
                broadcast(SetBombState(allocatedId, state = 1))
            }
        }
        return allocatedId
    }

    fun getBomb(bombId: Int): Bomb? {
        synchronized(bombs) {
            return bombs.find { it.id == bombId }
        }
    }

    /** 结算后停止对局仿真循环与水泡状态（保留 backToRoomFuture 等待自动回房）。 */
    fun stopSimulation() {
        synchronized(matchLock) {
            tickFuture?.cancel(false)
            tickFuture = null
        }
        clearSimulationState()
    }

    /** 回到房间或强制重置时销毁整场战斗：取消全部定时任务并清空所有战斗状态。 */
    fun destroy() {
        synchronized(matchLock) {
            if (phase == Phase.DESTROYED) return
            phase = Phase.DESTROYED
            tickFuture?.cancel(false)
            gameOverFuture?.cancel(false)
            backToRoomFuture?.cancel(false)
            tickFuture = null
            gameOverFuture = null
            backToRoomFuture = null
        }
        clearSimulationState()
    }

    private fun clearSimulationState() {
        synchronized(bombs) { bombs.clear() }
        movableBoxes.clear()
        movableBoxSentAt.clear()
        bombMoves.clear()
        bombMoveSentAt.clear()
        dartDetonations.clear()
        dartDetonationFutures.forEach { it.cancel(false) }
        dartDetonationFutures.clear()
        launchedBombActivations.values.forEach { it.cancel(false) }
        launchedBombActivations.clear()
    }
}
