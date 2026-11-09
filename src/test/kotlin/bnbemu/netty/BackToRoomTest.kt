package bnbemu.netty

import bnbemu.packet.room.BackToRoom
import bnbemu.packet.room.GameResult
import bnbemu.packet.room.InitGameResult
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.Room
import bnbemu.server.User
import bnbemu.server.state
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S→C op 155 BackToRoom（对局结束回房间）回归测试
 *
 * 逆向依据见 packet/room/BackToRoom.kt：
 *   · 客户端没有任何本地超时会把结果窗关掉，回房必须由服务端下令；
 *   · 局内分发器 0x55A4D8 的 case 155 → 0x54FC14 → 末尾 sub_68DC53(CStageManager, 12)，
 *     是全 exe 里唯一能从游戏阶段切回房间的路径；
 *   · 3 字节槽位状态 = {state, x, y}，state 与 op105 的 Slot.meta[0] 同一套（0x02 = 房主）。
 *     实机验证：旧实现一律写 0 会把房主位清掉，回房后「开始」按钮退化成「准备」。
 */
class BackToRoomTest {

    private lateinit var ch: EmbeddedChannel
    private lateinit var cs: ClientSocket
    private var savedGameOverDelay = 0L
    private var savedReturnDelay = 0L
    private var savedChain = true

    @BeforeEach
    fun setUp() {
        cs = ClientSocket()
        ch = EmbeddedChannel(cs)
        savedGameOverDelay = Room.gameOverDelayMs
        savedReturnDelay = Room.resultReturnDelayMs
        savedChain = Room.reenterRoomAfterBack
        Room.gameOverDelayMs = 0
        Room.resultReturnDelayMs = 0
        Room.reenterRoomAfterBack = false // 默认只验 155，房间链单独测

        Room.slots.forEach { slot ->
            slot.user = null
            slot.isAI = false
            slot.state = 0
        }
        Room.slots[0].user = User(cs, id = 1, name = "tester")
        Room.slots[0].state = FLAG_CHIEF
        for (i in 1..7) Room.slots[i].isAI = true
        Room.reset()
    }

    @AfterEach
    fun tearDown() {
        Room.gameOverDelayMs = savedGameOverDelay
        Room.resultReturnDelayMs = savedReturnDelay
        Room.reenterRoomAfterBack = savedChain
        Room.slots.forEach { slot ->
            slot.user = null
            slot.isAI = false
            slot.state = 0
        }
        Room.reset()
        ch.finish()
    }

    private fun opcodeOf(pkt: PKT): Int = OpcodeManager.getSendOp(pkt::class.java)

    private fun bodyOf(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        val body = ByteArray(buf.readableBytes())
        buf.readBytes(body)
        return body
    }

    /** 3 度帧 Encode1 ^0x5A */
    private fun dec1(b: Byte): Int = (b.toInt() and 0xFF) xor 0x5A

    @Suppress("UNCHECKED_CAST")
    private fun drain(): List<PKT> = ch.outboundMessages().toList() as List<PKT>

    private fun waitFor(op: Int, timeoutMs: Long = 2000): List<PKT> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val list = drain()
            if (list.any { opcodeOf(it) == op }) return list
            Thread.sleep(10)
        }
        return drain()
    }

    // ---- 包编码 ----

    @Test
    fun testOpcodeIs155() {
        assertEquals(155, OpcodeManager.getSendOp(BackToRoom()::class.java), "BackToRoom 必须绑到 op 155")
    }

    /**
     * 最小体 13B = E1(chState) + 3 × [E1(1) + {state, x, y}]。
     * 槽 0 必须是 FLAG_CHIEF，否则回房后房主位丢失（实机验证过）。
     */
    @Test
    fun testMinimalBodyLayout() {
        val body = bodyOf(BackToRoom())
        assertEquals(13, body.size, "最小体必须是 1 + 3×4 字节")
        assertEquals(0, dec1(body[0]), "首字节 chState")

        val states = (0 until 3).map { dec1(body[1 + it * 4 + 1]) }
        assertEquals(listOf(FLAG_CHIEF, 0, 0), states, "槽 0 必须保住房主位, 槽 1/2 = AI 空状态")

        for (i in 0 until 3) {
            val base = 1 + i * 4
            assertEquals(1, dec1(body[base]), "第 $i 条必须走 flag != 0 短分支")
            assertEquals(0, dec1(body[base + 2]), "第 $i 条 x")
            assertEquals(0, dec1(body[base + 3]), "第 $i 条 y")
        }
    }

    @Test
    fun testChStateIsEchoed() {
        val body = bodyOf(BackToRoom(chState = 3))
        assertEquals(3, dec1(body[0]))
    }

    @Test
    fun testSlotStatesComeFromRoomSlots() {
        Room.slots[1].state = 0x04 // 准备位
        val body = bodyOf(BackToRoom())
        assertEquals(FLAG_CHIEF, dec1(body[2]), "槽 0 房主位")
        assertEquals(0x04, dec1(body[6]), "槽 1 应回显 Room.slots[1].state")
    }

    // ---- 对局结束链路 ----

    @Test
    fun testMatchEndSendsBackToRoomAfterResult() {
        Room.startGame()
        drain()

        // 逐个击杀 AI，最后一人阵亡即触发结算
        for (slot in 1..7) Room.onBomberDie(slot)

        val sent = waitFor(155)
        val ops = sent.map(::opcodeOf)
        assertTrue(ops.contains(169), "必须先结算 op 169, ops=$ops")
        assertTrue(ops.contains(150), "必须先弹出结果窗 op 150, ops=$ops")
        assertTrue(ops.contains(155), "结算后必须补发 op 155 才会回房间, ops=$ops")
        assertTrue(
            ops.indexOf(155) > ops.indexOf(150),
            "op 155 必须在 op 150 之后（先让结果窗弹出来）, ops=$ops"
        )
    }

    @Test
    fun testBackToRoomCanBeTriggeredManually() {
        assertTrue(Room.backToRoom(force = true), "手动触发应真的发出包")
        val ops = drain().map(::opcodeOf)
        assertEquals(1, ops.count { it == 155 }, "手动触发只发一条 op 155, ops=$ops")
    }

    @Test
    fun testBackToRoomDedupesPerMatch() {
        assertTrue(Room.backToRoom(), "首次下发")
        assertTrue(!Room.backToRoom(), "同一局第二次调用应被去重")
        assertEquals(1, drain().count { opcodeOf(it) == 155 })
        assertTrue(Room.backToRoom(force = true), "force 可绕过去重（调试重试用）")
        assertEquals(2, drain().count { opcodeOf(it) == 155 })
    }

    @Test
    fun testRoomChainFollowsBackToRoom() {
        Room.reenterRoomAfterBack = true
        Room.backToRoom(force = true)
        // 房间链第一条是 op 87 RoomList
        val sent = waitFor(87)
        val ops = sent.map(::opcodeOf)
        assertTrue(ops.contains(155), "先发 155, ops=$ops")
        assertTrue(ops.contains(87), "155 之后必须补推房间链 (87 RoomList), ops=$ops")
        assertTrue(ops.indexOf(87) > ops.indexOf(155), "房间链必须在 155 之后, ops=$ops")
    }

    @Test
    fun testBackToRoomIsNotSentWithoutMatchEnd() {
        Room.startGame()
        drain()
        Room.onBomberDie(1) // 还有 AI 存活，不结算
        Thread.sleep(120)
        val ops = drain().map(::opcodeOf)
        assertTrue(ops.none { it == 155 }, "未结算时不得下发 op 155, ops=$ops")
        assertTrue(ops.none { it == 150 }, "未结算时不得下发 op 150, ops=$ops")
        // 对照：确认 GameResult 未被误发（防止上面的断言因包类名映射失败而空过）
        assertTrue(OpcodeManager.getSendOp(GameResult(0)::class.java) == 150)
        assertTrue(OpcodeManager.getSendOp(InitGameResult(0, 0, 0)::class.java) == 169)
    }
}
