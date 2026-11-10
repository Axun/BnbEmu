package bnbemu.server

import io.netty.channel.EventLoop
import io.netty.util.concurrent.ScheduledFuture
import bnbemu.isEN
import bnbemu.isJP
import bnbemu.netty.ClientSocket
import bnbemu.netty.NettyServer.workerGroup
import bnbemu.netty.PKT
import bnbemu.packet.ClientStage
import bnbemu.packet.room.*
import org.tinylog.kotlin.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// 之後改用class
object Room {
    private val executor: EventLoop = workerGroup.next()
    private var tickFuture: ScheduledFuture<*>? = null
    private var nextBombId = 0
    val slots = Array(8) { Slot() }
    var mapId = 89
    var themeId = 0
    val bombs = mutableListOf<Bomb>()
    var lastAirplaneTime = 0L

    /**
     * 房间号 / 会话 id —— S→C op 83 [bnbemu.packet.EnterSessionResult] 的第 3 栏位。
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

    val activeSlotCount: Int
        get() = slots.count { it.user != null || it.isAI }

    val uniqueClients: Sequence<ClientSocket>
        get() = slots.asSequence()
            .mapNotNull { it.user?.c } // 取得所有slot中有玩家的 ClientSocket
            .filter { runCatching { it.ch }.isSuccess && runCatching { it.ctx }.isSuccess }  // 初始化，防止在单元测试等场景抛出异常
            .distinctBy { it.ch.id() } // 過濾掉 2P 的重複連線 對2P只會發一次

    fun startGame() {
        // 每 200ms 一次炸弹与对局状态检测
        tickFuture?.cancel(false)
        tickFuture = executor.scheduleAtFixedRate(::onTick, 0, 200, TimeUnit.MILLISECONDS)

        // 每局开局把槽位生命状态重置成 (座位上有人 ? ALIVE : OUT_OF_MATCH) 并清结算标志。
        // 必须在这里做, 否则上一局阵亡的槽位会带着 DEAD 进入下一局,
        // 导致开局第一次死亡上报被当成重复上报吞掉、或开局即判定全灭。
        resetMatchState()

        if (!isEN) {
            // temp fix jp crash
            if (isJP)
                mapId = 118

            val spawnPositions = (0..7).shuffled()
            val random = Random.nextInt(9999)
            BC(
                LaunchGameStage(spawnPositions),
                AirplaneItemProb(),
                DoStartGame(random)
            )
        }
        // EN: 对局推进完全由客户端本地 AI (CAIPlayer / CAIGoal*) 与本地时钟驱动,
        // 服务端只在 C→S op 106 时回 S→C op 139、2.5s 后回 op 138 引爆, 无需任何周期广播。
        // (曾误加每 400ms 的 op 172 "ItemThrow" 广播, 实为地图事件时间轴同步, 见 MapEventSync.kt)
    }

    // CGameSession::Update - 定时更新水泡倒计时并广播爆炸状态
    // 注意: S→C op 138 SetBombState(bombId, 0) (CA.exe 0x005481BD -> 0x00534B18 -> 0x00565E9F)
    // 已完整处理水泡引爆、十字水柱、炸箱子与爆出道具。
    // 绝不可在此追加发送 S→C op 148 (CA.exe 0x0054BA1A -> 0x00477CEF), 因为 op 148 实为
    // DropObstacleResult (放置地面障碍物: 1=地雷, 2=陷阱, 3=香蕉皮 Item/BananaGround, 4=图钉 Item/PushpinGround)!
    private fun onTick() {
        val now = System.currentTimeMillis()
        val stateChanged = mutableListOf<Bomb>()

        synchronized(bombs) {
            bombs.removeIf { bomb ->
                if (bomb.updateState(now)) {
                    stateChanged.add(bomb)
                }
                bomb.shouldRemove()
            }
        }

        if (stateChanged.isNotEmpty()) {
            stateChanged.forEach { bomb ->
                BC(SetBombState(bomb.id, bomb.state))
            }
        }
    }

    // 客户端上报槽位事件 (Opcode 149) 时触发
    fun onEventItem(slotId: Int, token: Int, action: Int, param: Int) {
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

    private val movableBoxes = ConcurrentHashMap<Int, MovableBoxState>()
    private val movableBoxSentAt = ConcurrentHashMap<Int, Long>()

    // 完全相同的推箱动作(同 boxSeq + 同 src/dst)在该窗口内只广播一次。
    //
    // 去重是必须的: 客户端 0x0054CABA 之后进入 0x5393C2 的待确认队列, OnTick (0x0053A700)
    // 每帧检查、重试计数 > 5 就把**同一个动作**再发一次。而客户端 0x565CE0 会先校验
    // "箱子此刻仍在 (gridX,gridY)" —— 同一个 src→dst 广播第二次必然校验失败, 各客户端会
    // 触发 0x549C35 往服务端回 C→S op 149 回滚上报。
    // 但窗口必须有限: 万一一端的箱子因为漏包/校验失败没动, 玩家再推一次时必须能重播自愈,
    // 否则那个箱子会永久卡死。窗口取 400ms, 远大于一次重发周期(帧级), 又短于人的连点间隔。
    // 箱子被推回去再推回来时 src/dst 都不同, 不会被误判成重复。
    internal var movableBoxDedupeWindowMs = 400L

    // 返回是否真的广播了 (重复上报不广播, 便于测试/调试)。
    fun onMovableBoxPush(
        boxSeq: Int,
        gridX: Int,
        gridY: Int,
        targetX: Int,
        targetY: Int,
        slotId: Int
    ): Boolean {
        // boxSeq/末尾字节都是 E1/E2 原样字段, 只做宽度兜底, 不做语义过滤:
        // 末尾字节是客户端 CGameMsgCache 待确认队列下标, 收到的这一端 (0x565CE0 第 6 参数) 并不使用,
        // 过滤掉反而可能让某些槽位的推箱永远不生效。
        if (boxSeq !in 0..0xFFFF || slotId !in 0..0xFF) return false

        val now = System.currentTimeMillis()
        val next = MovableBoxState(gridX, gridY, targetX, targetY, slotId)
        val prev = movableBoxes[boxSeq]
        if (prev == next && now - (movableBoxSentAt[boxSeq] ?: 0L) < movableBoxDedupeWindowMs) {
            return false
        }
        movableBoxes[boxSeq] = next
        movableBoxSentAt[boxSeq] = now

        // 广播给所有客户端(含推箱者本人): 推箱者自己也需要这一包才会看到箱子动
        BC(MovableBoxMove(boxSeq, gridX, gridY, targetX, targetY, slotId))
        return true
    }

    fun movableBoxState(boxSeq: Int): MovableBoxState? = movableBoxes[boxSeq]

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
    // 窗口 400ms: 远大于帧级重发周期, 又短于玩家把球踢回同一位置的连击间隔。
    internal var bombMoveDedupeWindowMs = 400L
    data class BombMoveState(
        val kind: Int, val bombId: Int,
        val fromX: Int, val fromY: Int, val toX: Int, val toY: Int
    )

    private val bombMoves = ConcurrentHashMap<Int, BombMoveState>()
    private val bombMoveSentAt = ConcurrentHashMap<Int, Long>()

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
        val now = System.currentTimeMillis()
        val next = BombMoveState(kind, bombId, fromX, fromY, toX, toY)
        val prev = bombMoves[bombId]
        if (prev == next && now - (bombMoveSentAt[bombId] ?: 0L) < bombMoveDedupeWindowMs) {
            return false
        }
        bombMoves[bombId] = next
        bombMoveSentAt[bombId] = now

        // 更新服务端水泡状态: 滑行期间暂停倒计时, 到点后由 Bomb.updateState 续 2s 再引爆
        getBomb(bombId)?.apply {
            gridX = toX
            gridY = toY
            isMoving = true
            expireAt = now + moveDuration.coerceIn(0, 10_000)
        }

        // 回显 = op 107 前 7 字节原样 (action = kind, 4..7 滑行 / 0..3 抛掷)。
        // oldX/oldY 传 from: 客户端 0x54BDEA 只用 oldX 与 bomb+0x9C 做校验, 不符也照常执行。
        BC(
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
    //   于是「杀光全部 AI」之后对局永远不会结束。现在按 Slot.matchState 统计双方存活人数, 任意一方全灭即结束对局:
    //     · 人类全灭            → AI 胜 (与原行为一致, 胜利者取一个仍活着的 AI 槽位)
    //     · AI 全灭 && 人类存活 → 人类胜 (胜利者取玩家槽位, GameResult winnerTeam = 0)
    //   结算包固定 1.5s 延迟下发: 客户端要先播完阵亡动画。
    //   顺序 op 169 InitGameResult → op 150 GameResult（缺 169/150 不弹结算窗）。
    fun onBomberDie(slotId: Int) {
        if (slotId !in slots.indices) return

        val slot = slots[slotId]
        // 只有"参战且存活"的槽位才接受阵亡上报:
        //   · DEAD       → 重复上报 (CGameMsgCache 重发 / 抓包残流 / 连续两次 OnDie), 忽略,
        //                  否则会重复广播 op 159 并可能触发第二套结算包;
        //   · OUT_OF_MATCH → 空槽的错位/迟到上报, 忽略, 绝不能给没有 bomber 的槽位发 op 159
        //                  (客户端 0x0054C378 会拿 slotId 去 session 的 bomber 数组取对象)。
        if (slot.matchState != SlotMatchState.ALIVE) return
        slot.matchState = SlotMatchState.DEAD

        // 阵亡广播 = 两条包配合:
        //   1) S→C op159 BomberDie(slot) —— 客户端 0x0054C378: 置状态 17、清队伍桶、
        //      播 0x37（实测这声听不见，所以**光发它等于没声音**）；
        //   2) S→C op146 state=5「死亡名单」—— 0x54E0B8: 把该 bomber 从场上摘除 + 刷 UI，
        //      结尾播 0x2A 死亡音。
        //   顺序必须先 159 再名单：名单会把 bomber 摘掉，之后再发 159 会因 `[bomber+8]==0` 提前返回。
        //   名单的通用头 slot 不能写死 0：客户端 0x54DDD1 会先按它取 bomber，空槽/已摘除
        //   会让整个包被跳过（连 0x2A 都不播）。用本次名单里第一个槽——判空发生在摘除之前。
        BC(BomberDie(slotId))
        BC(BomberDeathList(listOf(slotId), headerSlot = slotId))
        println(
            "[Room] BomberDie slot=$slotId (" + (if (slot.user != null) "人类" else if (slot.isAI) "AI" else "空槽") +
                ") | 存活人类=${slots.indices.filter { slots[it].user != null && slots[it].isAlive }} " +
                "存活AI=${slots.indices.filter { slots[it].isAI && slots[it].isAlive }}"
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
        if (slotId !in slots.indices) return
        if (gameOverSent) return

        // 单座位房 (1 人无 AI) 客户端从第一帧就会满足"只剩一队", 那不是胜负而是没对手;
        // 真正参战的房间至少要有两个座位 (练习房 = 1 真人 + 1..7 AI)
        if (activeSlotCount < 2) return

        val winner = slots[slotId]
        // 空槽上报丢弃: 客户端只会替"真人或 AI"的存活槽位发这个包 (vtable[0x150] == 0 的过滤)
        if (!winner.hasSeat()) return

        val humanWin = winner.user != null
        // 落败方一律补记 DEAD, 让 matchState 与服务端认知一致 (客户端本地早就判死了)
        slots.forEach { s ->
            val sameSide = if (humanWin) s.user != null else s.isAI
            if (s.hasSeat() && !sameSide) s.matchState = SlotMatchState.DEAD
        }

        // action=0x60 是每帧重发的, 这里靠 gameOverSent 去重
        emitMatchResult(humanWin, slotId)
    }

    /**
     * 一方全灭即结束对局。调用前必须已经把阵亡槽位标记为 [SlotMatchState.DEAD]。
     * 只有[人类]与[AI]两个阵营都还有人时才继续对局; 任一方空出则立刻结算。
     */
    private fun finishMatchIfDecided() {
        if (gameOverSent) return

        // 阵营划分: 有人坐着 = 人类阵营, isAI = AI 阵营。不参战的空槽两边都进不去。
        val humanIdx = slots.indices.filter { slots[it].user != null }
        val enemyIdx = slots.indices.filter { slots[it].isAI }
        if (humanIdx.isEmpty()) return          // 没有人类就没有"人类胜负"可言, 交给上层流程

        val humanAlive = humanIdx.filter { slots[it].isAlive }
        val enemyAlive = enemyIdx.filter { slots[it].isAlive }

        // 结束条件只有两个: 人类全灭, 或 AI 全灭 (enemyIdx 为空时不存在"AI 全灭"这种胜负)。
        // 这样即使将来开了纯人类 PvP 房, 死一个人也不会被误判成"敌人清空"。
        val humanWiped = humanAlive.isEmpty()
        val enemyWiped = enemyIdx.isNotEmpty() && enemyAlive.isEmpty()
        if (!humanWiped && !enemyWiped) return   // 双方都还有人, 继续打

        // 人类全灭(含与 AI 同时全灭) → AI 胜, 沿用旧的 winnerSlotId = 1 语义;
        // 若还有 AI 活着, 胜利跳舞交给真正活下来的那个槽位。
        val humanWin = !humanWiped
        val winnerSlot = when {
            humanWin -> humanAlive.first()
            enemyAlive.isNotEmpty() -> enemyAlive.first()
            else -> 1
        }

        emitMatchResult(humanWin, winnerSlot, "存活人类=$humanAlive 存活AI=$enemyAlive")
    }

    /**
     * 真正下发结算包。两条触发路径共用:
     *   · [finishMatchIfDecided]  —— 靠 op154 action=5 逐个记录阵亡后的服务端统计
     *   · [onLastTeamStanding]    —— 靠 op112 action=0x60 客户端自报"只剩一支队伍"
     *
     * 固定顺序 op 169 InitGameResult → op 150 GameResult（客户端 0x55856A → 0x5584CC 建
     * CGameResultDocument，op 150 再填金币/经验并弹窗）。
     */
    private fun emitMatchResult(humanWin: Boolean, winnerSlot: Int, detail: String = "") {
        if (gameOverSent) return
        gameOverSent = true
        println(
            "[Room] 对局结束: " + (if (humanWin) "玩家胜" else "AI 胜") +
                " | winnerSlot=$winnerSlot" + (if (detail.isEmpty()) "" else " | $detail")
        )

        executor.schedule({
            BC(
                InitGameResult(mode = 0, mapId = 0),
                GameResult(
                    winnerTeam = if (humanWin) 0 else 1,
                    delayMs = 2000,
                    expAfter = if (humanWin) winExpAfter else loseExpAfter,
                    lucciGained = if (humanWin) winLucci else loseLucci
                )
            )
            // 结算后停掉对局主循环 (水泡倒计时), 与客户端离开棋盘同步
            endGame()
            // ⚠️ 结果窗弹出 ≠ 对局结束: 客户端没有任何本地超时会把结果窗关掉
            // (op150 的 delayMs 只决定「多久后弹窗」, 见 packet/room/BackToRoom.kt)。
            // 必须由服务端补一条 S→C op 155, 客户端才会拆掉 CGameStage 回到 stage 12 房间。
            executor.schedule({ backToRoom() }, resultReturnDelayMs, TimeUnit.MILLISECONDS)
        }, gameOverDelayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 下发 S→C op 155 [BackToRoom] —— 唯一能把客户端从游戏阶段拉回房间的包。
     *
     * 时序参照真实服务器「结果窗显示几秒后自动返回」: 结算包之后 [resultReturnDelayMs] 再发。
     *
     * ⚠️ 光有 155 只能把客户端「切回 stage 12」，房间对象/会话是空的（实机验证
     * 能回房、AI 还在，但「开始」按钮退化成「准备」= 房主位丢了）。
     * 所以默认再补推整条房间链（[sendRoomEntryChain]，与 C→S op 75 的回应同一串），
     * 把房主/座位/房间名/选图重新灌一遍；[reenterRoomAfterBack] 置 false 可只发 155。
     *
     * 调试入口: 聊天框 `/back`（仅 debugMode）—— 可强制重发、可指定槽位状态字节、可只发 155。
     *
     * @param sendRoomChain 是否在 155 之后补推房间链
     * @param slotStates    槽 0..2 的状态字节（null = 用 [slots] 的真实 state，槽 0 保住房主位）
     * @param chState       op155 首个 E1（`0x687393` → player+0x1AC，语义未定，默认 0）
     * @param force         true = 忽略「本局已发过」的去重（调试重试用）
     * @return 是否真的发出了包
     */
    fun backToRoom(
        sendRoomChain: Boolean = reenterRoomAfterBack,
        slotStates: List<Int>? = null,
        chState: Int = 0,
        force: Boolean = false
    ): Boolean {
        if (backSent && !force) return false
        backSent = true

        val states = slotStates ?: (0 until 3).map { slots.getOrNull(it)?.state?.toInt() ?: 0 }
        BC(BackToRoom(chState = chState, slotStates = states))
        // op 155 让客户端切回 stage 12; 同步服务端舞台状态, 之后从房间进商城才能正确返回房间
        uniqueClients.forEach { it.stage = ClientStage.Room.value }
        Logger.info(
            "[Room] 下发 op155 BackToRoom（回房间, stage 12）| chState=$chState " +
                "slotStates=$states roomChain=$sendRoomChain"
        )

        if (sendRoomChain) {
            uniqueClients.forEach { sendRoomEntryChain(it) }
            Logger.info("[Room] op155 之后补推房间链sendRoomEntryChain")
        }
        return true
    }

    /**
     * 调试专用：只广播 S→C op159 [BomberDie]，**不改** `matchState`、**不触发**结算。
     *
     * 用途：验证"op159 到底会不会让客户端播死亡音效 / 进入状态 17"。
     * 对局中在聊天框输入 `/die <slot>` 即可触发（见 handler/ChatMsg.kt，仅 debugMode）。
     */
    fun debugBroadcastBomberDie(slotId: Int) {
        if (slotId !in slots.indices) return
        println("[Room][debug] 手动广播 BomberDie slot=$slotId")
        BC(BomberDie(slotId))
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
        if (slotId !in slots.indices) return
        if (!slots[slotId].hasSeat()) return
        BC(SetBomberState(slotId, state, x, y, saverSlot))
        println("[Room] SetBomberState slot=$slotId state=$state x=$x y=$y" + if (saverSlot >= 0) " saver=$saverSlot" else "")
    }

    /**
     * 把某个槽位的 bomber 装进泡泡（op146 state 2，客户端 0x54DEE5）。
     *
     * ⚠️ 必须传目标**当前坐标**：state 2 的第一步就是 `vt[0x64](x, y)` 设位置，
     *    传 0,0 会把角色瞬移到地图左上角（实测"发完 trap 人物就不见了"）。
     *    坐标来源只有 C→S op112 上报过的 [Slot.posX]/[Slot.posY]（AI 也有，客户端会替它发）。
     *
     * ⚠️ 只发 state 2 就够：实测再补 state 9 只会让目标套上护盾（见 [shieldBomber]），
     *    并不会让"撞上去"变成破泡。客户端的破泡分支判的是 `0x45D15B` = `[+0x2AB8] >= 0`，
     *    而 `+0x2AB8` 是**护盾计时**（0x45E923 / 0x45E998），不是被困计时。
     *
     * @param x/y 显式坐标；不传则用该槽位最后上报的位置，未知时拒绝下发（宁可不动也不能瞬移）。
     * @return 是否真的发出了包
     */
    fun trapBomber(slotId: Int, x: Int = -1, y: Int = -1): Boolean {
        if (slotId !in slots.indices) return false
        val slot = slots[slotId]
        if (!slot.hasSeat()) return false
        if (x < 0 || y < 0) {
            println("[Room] 无法困住 slot=$slotId: 没有坐标")
            return false
        }
        BC(SetBomberState(slotId, BOMBER_STATE_TRAPPED, x, y))
        println("[Room] 困住 slot=$slotId: state=2 x=$x y=$y")
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
        if (slotId !in slots.indices) return false
        val slot = slots[slotId]
        if (!slot.hasSeat()) return false

        if (saverSlot < 0) {
            // 12 = 脱掉气泡但**不改坐标**（角色留在原地）—— 实测最安全。
            // 不要顺手发 state 10：那是"破盾"，会把 target 的护盾（如果有）一起打掉并播 0x87。
            BC(SetBomberState(slotId, BOMBER_STATE_FREE))
            println("[Room] 脱困 slot=$slotId: state=12")
            return true
        }

        if (x < 0 || y < 0) {
            println("[Room] 无法用 state 3 救下 slot=$slotId: 没有坐标, 退回 state 12")
            BC(SetBomberState(slotId, BOMBER_STATE_FREE))
            return true
        }
        BC(SetBomberState(slotId, BOMBER_STATE_SAVED, x, y, saverSlot))
        println("[Room] SetBomberState slot=$slotId state=${BOMBER_STATE_SAVED}(被救) x=$x y=$y saver=$saverSlot")
        return true
    }

    /**
     * 广播"死亡名单"（op146 state=5）—— 真正用来通知阵亡的消息。
     *
     * 调试入口：聊天框 `/kill <slot>[ <slot>...]`（仅 debugMode）。**没有自动调用点**。
     */
    fun broadcastDeathList(slotIds: List<Int>, debug: Boolean = false) {
        val valid = slotIds.filter { it in slots.indices && slots[it].hasSeat() }
        if (valid.isEmpty()) return
        // 通用头指向名单第一个槽：客户端判空在 case5 摘除之前，此刻它一定还在。
        BC(BomberDeathList(valid, headerSlot = valid.first()))
        println("[Room] BomberDeathList slots=$valid" + (if (debug) " (debug)" else ""))
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
        if (slotId !in slots.indices) return false
        if (gameOverSent) return false              // 已经结算, 复活没有意义
        val slot = slots[slotId]
        if (!slot.hasSeat()) return false           // 空槽没有 bomber 可复活
        if (slot.matchState != SlotMatchState.DEAD) return false

        slot.matchState = SlotMatchState.ALIVE
        BC(ReviveBomber(slotId))
        println("[Room] ReviveBomber slot=$slotId")
        return true
    }

    // 结算结果包的下发延迟。测试可置 0 以便同步断言。
    internal var gameOverDelayMs = 1000L
    // 结算包之后多久下发 S→C op 155 [BackToRoom]（客户端据此退出游戏阶段回到房间）。
    // 真实服务器是「结果窗出来后过几秒自动返回」；测试可置 0 以便同步断言。
    internal var resultReturnDelayMs = 7000L
    // op155 之后是否补推整条房间链（重建房主位/座位/房间对象）。置 false = 只发 155（对照用）。
    internal var reenterRoomAfterBack = true
    // 本局是否已经发过回房间包
    internal var backSent = false

    // ---- 结算奖励（op 150 GameResult） ----
    // EXP 是「等级进度千分比」：1000 = 100.0%，结果页会从 0.0% 涨到这个值
    // （客户端 0x53E617 = min(+124 + 涨条动画, +128)，见 packet/room/GameResult.kt）。
    // LUCCI 是本局获得金币（+152，有符号）。
    // 输的一档按胜利的 1/3 给：输局也要有收益，但不能和赢一样多。
    internal var winExpAfter = 60   // 胜利 6.0%
    internal var loseExpAfter = 20  // 失败 2.0%
    internal var winLucci = 120
    internal var loseLucci = 40

    // 是否已经下发过本局结算 (防止重复结算)
    internal var gameOverSent = false

    fun addBomb(
        slotId: Int,
        gridX: Int,
        gridY: Int,
        isSpecial: Boolean = false,
        unk: Int = 0,
        bombId: Int? = null,
        broadcastInitialState: Boolean = true
    ): Int {
        val allocatedId = bombId ?: nextBombId++
        if (allocatedId >= nextBombId) {
            nextBombId = allocatedId + 1
        }
        executor.execute {
            val bomb = Bomb(allocatedId, slotId, (gridY shl 8) or gridX, 0, isSpecial).apply {
                this.gridX = gridX
                this.gridY = gridY
                this.expireAt = System.currentTimeMillis() + 2500
            }
            synchronized(bombs) {
                bombs.removeIf { it.id == allocatedId }
                bombs += bomb
            }
            if (broadcastInitialState) {
                BC(SetBombState(allocatedId, state = 1))
            }
        }
        return allocatedId
    }

    fun getBomb(bombId: Int): Bomb? {
        return bombs.find { it.id == bombId }
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
            val user = slot.user
            if (user != null) {
                c send SetPlayer(index, slot, user)
            } else {
                c send SetSlotState(index, slot)
            }
        }
    }

    fun endGame() {
        tickFuture?.cancel(false)
        bombs.clear()
        movableBoxes.clear()
        movableBoxSentAt.clear()
    }

    // 座位上有人 (人类玩家或 AI)：这是"能不能参战"的判据, 只看座位占用, 不看本局死活。
    private fun Slot.hasSeat(): Boolean = user != null || isAI

    /**
     * 重置一局的参战/存活状态与结算标志 (开局时调用)。
     *
     * 参战槽位 (座位上有人) 置 ALIVE，空槽置 OUT_OF_MATCH —— 保留"空槽"这个第三态，
     * 而不是简单把所有槽位都置 ALIVE，否则 [onBomberDie] 的守卫挡不住
     * "给根本没有 bomber 的槽位广播 op 159" 这类错位/迟到上报。
     *
     * 这里同时把"谁参战"定格成一局开始时的快照：对局中途有人离开也不会把他从结算名单里
     * 抹掉（人是这一局的参战者，离开只是掉线）。
     *
     * ⚠️ 将来若支持"中途入座"，新玩家入座时必须把该槽位置回 [SlotMatchState.ALIVE]，
     *    否则他的阵亡上报会被当成"不参战/重复上报"吞掉，该玩家阵亡后对局永远不会结束。
     */
    fun resetMatchState() {
        gameOverSent = false
        backSent = false
        slots.forEach {
            it.matchState = if (it.hasSeat()) SlotMatchState.ALIVE else SlotMatchState.OUT_OF_MATCH
        }
    }

    fun reset() {
        tickFuture?.cancel(false)
        bombs.clear()
        movableBoxes.clear()
        movableBoxSentAt.clear()
        bombMoves.clear()
        bombMoveSentAt.clear()
        nextBombId = 0
        lastAirplaneTime = 0L
        resetMatchState()
    }
}
