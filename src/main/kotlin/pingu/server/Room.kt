package pingu.server

import io.netty.channel.EventLoop
import io.netty.util.concurrent.ScheduledFuture
import pingu.isEN
import pingu.isJP
import pingu.netty.ClientSocket
import pingu.netty.NettyServer.workerGroup
import pingu.netty.PKT
import pingu.packet.room.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// 之後改用class
object Room {
    private val executor: EventLoop = workerGroup.next()
    private var tickFuture: ScheduledFuture<*>? = null
    private var aiTickFuture: ScheduledFuture<*>? = null
    private var gameStartMs = 0L
    private var nextBombId = 0
    val slots = Array(8) { Slot() }
    var mapId = 89
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

        if (isEN) {
            // 真实 EN 服务器在对局中每 ~400ms 广播一个 op 172 (ItemThrow/实体时间同步),
            // 见 tst.pcap stream4 sq=343~400。移除它会让客户端局内实体推进停摆。
            gameStartMs = System.currentTimeMillis()
            aiTickFuture?.cancel(false)
            aiTickFuture = executor.scheduleAtFixedRate(::aiTick, 400, 400, TimeUnit.MILLISECONDS)
        } else {
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
    }

    // CGameSession::Update - 定时更新水泡倒计时并广播爆炸状态
    private fun onTick() {
        val now = System.currentTimeMillis()
        val stateChanged = mutableListOf<Bomb>()

        bombs.removeIf { bomb ->
            if (bomb.updateState(now)) {
                stateChanged.add(bomb)
            }
            bomb.shouldRemove()
        }

        if (stateChanged.isNotEmpty()) {
            stateChanged.forEach { bomb ->
                BC(SetBombState(bomb.id, 0))
                BC(TileBombExplode(bomb.ownerSlotId, bomb.gridX, bomb.gridY, power = 1))
            }
        }
    }

    // 客户端上报槽位事件 (Opcode 149) 时触发
    fun onEventItem(slotId: Int, token: Int, action: Int, param: Int) {
    }

    // 对局进行中周期性广播 op 172 (实体/道具时间同步) — 复刻真实服务器 ~400ms 节奏。
    // body 的 E4 为对局开始以来的毫秒数 (抓包样本 401/801/1201/2002... 与墙钟吻合)。
    private fun aiTick() {
        val elapsed = (System.currentTimeMillis() - gameStartMs).toInt()
        val flag = (elapsed / 400) % 4
        BC(ItemThrow(elapsed, flag))
    }


    fun addBomb(slotId: Int, gridX: Int, gridY: Int, isSpecial: Boolean = false, unk: Int = 0) {
        executor.execute {
            val bombId = nextBombId++
            val bomb = Bomb(bombId, slotId, (gridY shl 8) or gridX, 0, isSpecial).apply {
                this.gridX = gridX
                this.gridY = gridY
            }
            bombs += bomb
            BC(BombIgnite(slotId, gridX, gridY, bombId, unk))
        }
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
        aiTickFuture?.cancel(false)
        bombs.clear()
    }

    fun reset() {
        tickFuture?.cancel(false)
        aiTickFuture?.cancel(false)
        bombs.clear()
        nextBombId = 0
        lastAirplaneTime = 0L
        gameStartMs = 0L
    }
}