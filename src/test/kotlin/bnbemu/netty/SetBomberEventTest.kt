package bnbemu.netty

import bnbemu.handler.room.SetBomberEventReq
import bnbemu.packet.room.SetBomberEvent
import bnbemu.packet.room.SetBombState
import bnbemu.server.Bomb
import bnbemu.server.Room
import bnbemu.server.User
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C→S op 106 / S→C op 139 的变长协议回归测试。
 *
 * 用户日志实测样本 (2026-11-14 坦克发射水泡, 16B body):
 *   A5 69 5A 96 CA 53 BD 50 5B 5A 7D 50 56 58 A2 B9
 *   → sessionId=0 slotId=0 bombToken=40 origin=(10,1) reserved=0 bombFlags=0x27
 *     landing=(10,12) launchDirection=2 flightRange=2000
 * bombFlags 的 0x20 位要求 S→C op 139 补写 3B 落点扩展; 漏写会让客户端 sub_54A3EA
 * 把 bombId 的字节当成落点坐标, 水泡被画到屏幕外。
 *
 * 后续实测发现发射泡「停在障碍前但永不爆炸」: 客户端 sub_4598AB 把泡置成
 * +192=0 未激活 / +292=1 飞行中, 只有本地撞图钉时会自行激活再引爆 (CA.exe.c:79375);
 * 服务端 state=0 路径 (sub_534B18) 没有激活补偿。故发射泡需要延时补 S→C op 138
 * state=1 落地激活 (等发射动画播完), 但不延长 expireAt, 且被提前引爆时跳过。
 */
class SetBomberEventTest {

    private val tankBombBody = "A5 69 5A 96 CA 53 BD 50 5B 5A 7D 50 56 58 A2 B9".toBA()
    private val plainBombBody = "A5 69 5A 96 CA 53 BD 50 5B 5A 5B".toBA()
    private val launchedFlagWithoutExtension = "A5 69 5A 96 CA 53 BD 50 5B 5A 7D".toBA()

    // 23:24 实测样本: token=60 origin=(1,10) landing=(8,10) dir=3 range=2000
    private val tankBombBodyToken60 = "A5 69 5A 96 CA 53 A9 5B 50 5A 7D 52 50 59 A2 B9".toBA()

    private val tankBombReply =
        "A5 69 5A 96 CA 53 BD 96 CA 53 95 96 CA 53 95 50 5B 5A 7D 50 56 58 A5 41 A5 69 5A".toBA()
    private val plainBombReply =
        "A5 69 5A 96 CA 53 BD 96 CA 53 95 96 CA 53 95 50 5B 5A 5B A5 41 A5 69 5A".toBA()
    private val fallbackReply =
        "A5 69 5A 96 CA 53 BD 96 CA 53 95 96 CA 53 95 50 5B 5A 7D 50 5B 5A A5 41 A5 69 5A".toBA()
    private val tankBombReplyToken60 =
        "A5 69 5A 96 CA 53 A9 96 CA 53 95 96 CA 53 95 5B 50 5A 7D 52 50 59 A5 55 A5 69 5A".toBA()

    // S→C op 138 SetBombState(bombToken, state=1) = E1(1) E2(id) E1(1)
    private val activationToken40 = "5B A5 41 5B".toBA()
    private val activationToken60 = "5B A5 55 5B".toBA()

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    /** Room.addBomb 在共享 EventLoop 上异步注册, 等它落地后再断言, 避免竞态。 */
    private fun awaitBomb(bombToken: Int): Bomb {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            synchronized(Room.bombs) { Room.getBomb(bombToken) }?.let { return it }
            Thread.sleep(5)
        }
        error("Room.addBomb 异步注册未生效: bombToken=$bombToken")
    }

    /**
     * 端到端: C→S op 106 → Room.BC → EmbeddedChannel 收到 S→C op 139。
     * 发射泡的落地激活是延时排程, 这里把延时调大避免后台任务干扰,
     * 由 [beforePackets] 显式调用 Room.activateLaunchedBomb 模拟「延时到点」。
     */
    private fun runBombRequest(
        body: ByteArray,
        bombToken: Int = 40,
        beforePackets: () -> Unit = {},
        assertions: (List<ByteArray>) -> Unit
    ) {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val previousUser = Room.slots[0].user
        val previousDelay = Room.launchedBombActivationDelayMs
        try {
            Room.slots[0].user = User(cs, id = 1, name = "tester")
            Room.launchedBombActivationDelayMs = 60_000L
            Room.reset()
            ch.outboundMessages().clear()

            val buf = Unpooled.wrappedBuffer(body)
            with(SetBomberEventReq) { buf.handle(cs) }
            assertEquals(0, buf.readableBytes(), "C→S body 必须被完整消费, 不能残留影响下一包")

            awaitBomb(bombToken)
            beforePackets()

            val sent = ch.outboundMessages().toList()
            assertions(sent.map { encode(it as PKT) })
        } finally {
            Room.slots[0].user = previousUser
            Room.launchedBombActivationDelayMs = previousDelay
            Room.reset()
            ch.finish()
        }
    }

    @Test
    fun testOpcodeBinding() {
        assertEquals("SetBomberEventReq", OpcodeManager.recvOps[106])
        assertNotNull(handlerArray[106], "Handler 106 must be registered")
        assertSame(SetBomberEventReq, handlerArray[106])
        assertEquals(139, OpcodeManager.getSendOp(SetBomberEvent(0, 0, 0)::class.java))
        assertEquals(138, OpcodeManager.getSendOp(SetBombState(60, 1)::class.java))
    }

    @Test
    fun testTankBombReplyIncludesLandingExtensionAndActivation() {
        runBombRequest(tankBombBody, beforePackets = {
            assertTrue(Room.launchedBombActivationPending(40), "发射泡必须排程延时激活, 不能立即发")
            assertTrue(Room.activateLaunchedBomb(40), "延时到点应下发 state=1")
            assertFalse(Room.launchedBombActivationPending(40), "激活只能执行一次")
        }) { replies ->
            val sent = replies[0]
            assertEquals(27, sent.size, "bombFlags & 0x20 时 op139 必须从 24B 变 27B")
            assertEquals(
                tankBombReply.toHexString(), sent.toHexString(),
                "落点 (10,12) 与方向 2 必须紧跟 bombFlags, bombId 不能被吃掉"
            )
            assertEquals(
                activationToken40.toHexString(), replies[1].toHexString(),
                "发射泡落地激活必须是 op138 state=1"
            )

            // 角色槽位仍是炮口, 服务端水泡表则登记落点
            assertEquals(10, Room.slots[0].gridX)
            assertEquals(1, Room.slots[0].gridY)
            val bomb = awaitBomb(40)
            assertEquals(10, bomb.gridX, "服务端水泡必须注册在落点 X")
            assertEquals(12, bomb.gridY, "服务端水泡必须注册在落点 Y, 不是炮口 (10,1)")
            assertTrue(bomb.isSpecial, "0x20 才是 isSpecial 判据, 旧的 flag0 != 0 判错了")
        }
    }

    @Test
    fun testLatestTankSampleActivatesBomb() {
        runBombRequest(tankBombBodyToken60, bombToken = 60, beforePackets = {
            assertTrue(Room.activateLaunchedBomb(60))
        }) { replies ->
            assertEquals(tankBombReplyToken60.toHexString(), replies[0].toHexString())
            assertEquals(activationToken60.toHexString(), replies[1].toHexString())

            assertEquals(1, Room.slots[0].gridX, "角色槽位记炮口 (1,10)")
            assertEquals(10, Room.slots[0].gridY)
            val bomb = awaitBomb(60)
            assertEquals(8, bomb.gridX, "服务端水泡登记落点 (8,10)")
            assertEquals(10, bomb.gridY)
            assertTrue(bomb.isSpecial)
        }
    }

    @Test
    fun testEarlyDetonationSkipsActivation() {
        // 射到钉子/飞镖提前引爆 (onObjectHit 立刻从 bombs 移除) → 延时到点不得再发激活包
        runBombRequest(tankBombBody, beforePackets = {
            assertTrue(
                Room.onObjectHit(targetObjectId = 999, gridX = 10, gridY = 12, delayMs = 0, broadcast = false),
                "按落点应能命中这颗泡"
            )
            assertFalse(Room.activateLaunchedBomb(40), "泡已被移除, 激活必须跳过")
        }) { replies ->
            assertEquals(1, replies.size, "提前引爆后不得再下发给死泡的 state=1 激活包")
        }
    }

    @Test
    fun testPlainBombReplyStays24BytesAndNeedsNoActivation() {
        runBombRequest(plainBombBody, beforePackets = {
            assertFalse(Room.launchedBombActivationPending(40), "普通泡不排程落地激活")
            assertFalse(Room.activateLaunchedBomb(40), "没有排程记录时激活应幂等跳过")
        }) { replies ->
            assertEquals(1, replies.size, "普通泡只回 op139 一包")
            assertEquals(24, replies[0].size, "普通放泡不得多写扩展字节")
            assertEquals(plainBombReply.toHexString(), replies[0].toHexString(), "11B 定长路径必须保持原样")
            assertFalse(awaitBomb(40).isSpecial, "普通水泡不是发射型, 不需要 state=1 激活")
            assertEquals(10, awaitBomb(40).gridX)
            assertEquals(1, awaitBomb(40).gridY)
        }
    }

    @Test
    fun testLaunchedFlagWithoutExtensionFallsBackToOrigin() {
        // 标志置位但扩展字节缺失 (跨包截断): 不得越界读取断线, 仍需补 3B 保持客户端读序对齐
        runBombRequest(launchedFlagWithoutExtension, beforePackets = {
            assertTrue(Room.activateLaunchedBomb(40))
        }) { replies ->
            assertEquals(27, replies[0].size, "只要 bombFlags 带 0x20, 回显就必须带 3B 扩展")
            assertEquals(fallbackReply.toHexString(), replies[0].toHexString(), "缺数据时落点退回发射点")
            assertEquals(activationToken40.toHexString(), replies[1].toHexString(), "标志置位就必须激活")
        }
    }

    @Test
    fun testTruncatedBodyIsSkippedWithoutBroadcast() {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val previousUser = Room.slots[0].user
        try {
            Room.slots[0].user = User(cs, id = 1, name = "tester")
            Room.reset()
            ch.outboundMessages().clear()

            val buf = Unpooled.wrappedBuffer("A5 69 5A 96 CA".toBA())
            with(SetBomberEventReq) { buf.handle(cs) }

            assertEquals(0, buf.readableBytes(), "残包必须被跳过, 不能抛异常断线")
            assertEquals(0, ch.outboundMessages().size, "残包不得回包")
        } finally {
            Room.slots[0].user = previousUser
            Room.reset()
            ch.finish()
        }
    }
}
