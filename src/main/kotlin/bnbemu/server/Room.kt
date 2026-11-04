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
    // 来源: C→S op 109 PushMovableBoxReq。客户端在 0x0054CABA 里已完成本地位移预测,
    // 本包是持续推动时的周期上报, 不需要任何 S→C ack; 服务端只留档, 供日后多人同步/校验使用。
    data class MovableBoxState(
        var gridX: Int,
        var gridY: Int,
        var targetX: Int,
        var targetY: Int,
        var slotId: Int
    )

    private val movableBoxes = ConcurrentHashMap<Int, MovableBoxState>()

    fun onMovableBoxPush(
        boxSeq: Int,
        gridX: Int,
        gridY: Int,
        targetX: Int,
        targetY: Int,
        slotId: Int
    ) {
        if (boxSeq !in 0..0xFFFF || slotId !in 0..7) return
        movableBoxes[boxSeq] = MovableBoxState(gridX, gridY, targetX, targetY, slotId)
    }

    fun movableBoxState(boxSeq: Int): MovableBoxState? = movableBoxes[boxSeq]

    // 角色死亡上报处理 (C→S op 154 action=5 -> CA.exe 0x0046DB1D CGOGameBomber::OnDie)
    fun onBomberDie(slotId: Int) {
        if (slotId in slots.indices) {
            // 广播 S→C op 159 BomberDie 触发客户端播放死亡动画与淘汰状态设置 (CA.exe 0x0054C378)
            BC(BomberDie(slotId))
            // 若玩家自己 (槽位 0) 阵亡，延迟 1.5 秒下发 GameOver 结束对局 (CA.exe 0x0055345D)
            // 紧接着下发 S→C op 169 InitGameResult 与 op 150 GameResult 弹出结算界面 (CA.exe 0x0055856A / 0x00559854)
            if (slotId == 0) {
                executor.schedule({
                    BC(
                        GameOver(winnerSlotId = 1),
                        InitGameResult(mode = 0, mapId = 0),
                        GameResult(winnerTeam = 1, delayMs = 2000)
                    )
                }, 1500, TimeUnit.MILLISECONDS)
            }
        }
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
    }

    fun reset() {
        tickFuture?.cancel(false)
        bombs.clear()
        movableBoxes.clear()
        nextBombId = 0
        lastAirplaneTime = 0L
    }
}
