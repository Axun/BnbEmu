package bnbemu.server

import io.netty.channel.EventLoop
import io.netty.util.concurrent.ScheduledFuture
import bnbemu.isEN
import bnbemu.isJP
import bnbemu.netty.ClientSocket
import bnbemu.netty.NettyServer.workerGroup
import bnbemu.netty.PKT
import bnbemu.packet.room.*
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

    val activeSlotCount: Int
        get() = slots.count { it.user != null || it.isAI }

    val uniqueClients: Sequence<ClientSocket>
        get() = slots.asSequence()
            .mapNotNull { it.user?.c } // 取得所有slot中有玩家的 ClientSocket
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
    // 结算判定规则 (2026-11-05 修正):
    //   旧实现只在 slotId == 0 (玩家自己阵亡) 时下发结算, 于是「杀光全部 AI」之后对局永远不会结束 ——
    //   客户端只是把每个阵亡的 bomber 转发给服务端, 自身没有任何"敌人清空"判定, 全部依赖服务端下令。
    //   现在按 Slot.matchState (OUT_OF_MATCH / ALIVE / DEAD) 统计双方存活人数, 任意一方全灭即结束对局:
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

        // 广播 S→C op 159 BomberDie 触发客户端播放死亡动画与淘汰状态设置 (CA.exe 0x0054C378)
        BC(BomberDie(slotId))
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
                GameResult(winnerTeam = if (humanWin) 0 else 1, delayMs = 2000)
            )
            // 结算后停掉对局主循环 (水泡倒计时), 与客户端离开棋盘同步
            endGame()
        }, gameOverDelayMs, TimeUnit.MILLISECONDS)
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
    internal var gameOverDelayMs = 1500L

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
        slots.forEach { it.matchState = if (it.hasSeat()) SlotMatchState.ALIVE else SlotMatchState.OUT_OF_MATCH }
    }

    fun reset() {
        tickFuture?.cancel(false)
        bombs.clear()
        movableBoxes.clear()
        movableBoxSentAt.clear()
        nextBombId = 0
        lastAirplaneTime = 0L
        resetMatchState()
    }
}
