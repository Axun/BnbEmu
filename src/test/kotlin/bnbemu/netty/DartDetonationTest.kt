package bnbemu.netty

import bnbemu.handler.battle.ObjectHitReq
import bnbemu.server.BattleSession
import bnbemu.server.Bomb
import bnbemu.server.Room
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DartDetonationTest {

    private lateinit var room: Room
    private lateinit var battle: BattleSession

    @BeforeEach
    fun setUp() {
        room = Room.enter(Room())
        battle = room.startGame()
    }

    @AfterEach
    fun tearDown() {
        Room.leave()
    }

    private fun addBomb(id: Int, x: Int, y: Int) {
        synchronized(battle.bombs) {
            battle.bombs += Bomb(id, 0, 0, 0, false).apply {
                gridX = x
                gridY = y
                state = 1
            }
        }
    }

    @Test
    fun hitDetonatesBombAndRemovesIt() {
        addBomb(540, 6, 5)

        val detonated = battle.onObjectHit(540, 6, 5, 96, broadcast = false)

        assertTrue(detonated)
        val state = battle.dartDetonationState(540)
        assertNotNull(state)
        state!!
        assertEquals(540, state.targetObjectId)
        assertEquals(6, state.gridX)
        assertEquals(5, state.gridY)
        assertEquals(96, state.delayMs)
        assertTrue(state.includeSender, "飞镖路径客户端没本地炸, 广播必须含发射者")
        assertFalse(battle.bombs.any { it.id == 540 }, "引爆后立刻移出 bombs, 防 onTick 2.5s 二次引爆")
    }

    @Test
    fun duplicateReportIsIgnored() {
        addBomb(541, 6, 5)
        assertTrue(battle.onObjectHit(540, 6, 5, 96, broadcast = false))
        assertFalse(
            battle.onObjectHit(540, 6, 5, 96, broadcast = false),
            "实测连发两镖会收到两条完全相同的 op108, 第二条不得重复引爆"
        )
    }

    @Test
    fun missDoesNotDetonate() {
        addBomb(542, 6, 5)
        assertFalse(battle.onObjectHit(540, 6, 4, 96, broadcast = false), "命中格无水泡时不应引爆")
        assertTrue(battle.bombs.any { it.id == 542 }, "未命中的水泡必须留在 bombs 里等 2.5s 定时")
    }

    @Test
    fun zeroDelayExcludesSender() {
        addBomb(543, 6, 5)
        assertTrue(battle.onObjectHit(540, 6, 5, 0, broadcast = false))
        assertFalse(battle.dartDetonationState(543)!!.includeSender, "delay=0 是客户端已本地引爆的路径")
    }

    @Test
    fun outOfRangeGridIgnored() {
        addBomb(544, 6, 5)
        assertFalse(battle.onObjectHit(540, 200, 5, 96, broadcast = false))
        assertTrue(battle.bombs.any { it.id == 544 })
    }

    @Test
    fun handlerTriggersDetonationEndToEnd() {
        addBomb(545, 6, 5)
        val raw = byteArrayOf(
            0xA7.toByte(), 0x75.toByte(), 0x5C.toByte(),
            0x5F.toByte(), 0xA5.toByte(), 0x09.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(ObjectHitReq) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes())
        assertNotNull(battle.dartDetonationState(545))
        assertEquals(96, battle.dartDetonationState(545)!!.delayMs)
        assertFalse(battle.bombs.any { it.id == 545 })
    }
}
