package bnbemu.server

/**
 * 单局战斗的可调参数与结算奖励配置。
 * 由 [Room.battleConfig] 持有默认值，每次开局创建 [BattleSession] 时传入快照，
 * 避免对局中途参数被修改导致行为不一致。
 */
data class BattleConfig(
    /** onTick 调度周期 (ms) */
    var tickIntervalMs: Long = 200L,
    /** 对局超时上限 (ms): 对齐真端 GAME_DURATION_LIMIT_MS = 0x2BF20 (3 分钟); <=0 表示不超时 */
    var matchTimeoutMs: Long = 180_000L,
    /** 结算结果包的下发延迟 (ms) */
    var gameOverDelayMs: Long = 1000L,
    /** 结算包之后多久下发 S→C op 155 [bnbemu.packet.battle.BackToRoom]; <0 表示不自动排程回房 */
    var resultReturnDelayMs: Long = 7000L,
    /** op155 之后是否补推整条房间链（重建房主位/座位/房间对象） */
    var reenterRoomAfterBack: Boolean = true,
    /**
     * 发射型水泡 (坦克炮泡) 的「落地激活」延时 (ms)。
     *
     * op139 的 sub_4598AB 会把泡置成 +192=0 未激活 / +292=1 飞行中, 必须等发射动画播完
     * 再补 S→C op138 state=1 (sub_534B18 -> sub_45976E) 才能被之后的 state=0 引爆。
     * 立即发会截断动画; 这里刻意不改 expireAt, 引信仍从收到 C→S op106 起算 2.5s。
     */
    var launchedBombActivationDelayMs: Long = 500L,
    /** 推箱子同动作去重窗口 (ms); 取值理由见 [BattleSession.onMovableBoxPush] 上方注释 */
    var movableBoxDedupeWindowMs: Long = 400L,
    /** 踢/推水泡同动作去重窗口 (ms); 取值理由见 [BattleSession.BombMoveState] 上方注释 */
    var bombMoveDedupeWindowMs: Long = 400L,
    /** 飞镖命中引爆的最大延时上限 (ms); 超过它客户端动画早已播完, 钳住避免迟到的引爆包 */
    var dartDetonationMaxDelayMs: Long = 500L,
    // ---- 结算奖励（op 150 GameResult） ----
    // EXP 是「等级进度千分比」：1000 = 100.0%，结果页会从 0.0% 涨到这个值
    // （客户端 0x53E617 = min(+124 + 涨条动画, +128)，见 packet/room/GameResult.kt）。
    // LUCCI 是本局获得金币（+152，有符号）。
    // 输的一档按胜利的 1/3 给：输局也要有收益，但不能和赢一样多。
    var winExpAfter: Int = 60,   // 胜利 6.0%
    var loseExpAfter: Int = 20,  // 失败 2.0%
    var drawExpAfter: Int = 30,  // 平局 3.0%
    var winLucci: Int = 120,
    var loseLucci: Int = 40,
    var drawLucci: Int = 60,
)
