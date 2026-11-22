package bnbemu.netty

import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.handler.room.StartGameReq
import bnbemu.packet.battle.GAME_START_SLOT_ENTRIES
import bnbemu.packet.battle.GameStart
import bnbemu.packet.battle.GameStartPeer
import bnbemu.packet.battle.UDP_PEER_ENDPOINTS
import bnbemu.packet.battle.UdpEndpoint
import bnbemu.server.Room
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S→C op 136 GameStart 的编码回归。
 *
 * 舊實現把 178B 抓包體整段重放（`packet/room/PracticeBlobs.kt` 的 EN_OP136），
 * 現在改成按字段生成；這裡用抓包原值反推參數，斷言生成的位元組與基準逐位元組一致，
 * 再逐槽解回來驗證佔用位與地址表的形狀。
 */
class GameStartTest {

    /** 頭部字節數: 4×E4 + E1(tick) + E4(ip) + E2(port) + E1(timeout)，不含 2B opcode。 */
    private val headerBytes = 4 + 4 + 4 + 4 + 1 + 4 + 2 + 1

    private lateinit var savedAI: List<Boolean>

    @BeforeEach
    fun setUp() {
        savedAI = Room.slots.map { it.isAI }
    }

    @AfterEach
    fun tearDown() {
        Room.slots.forEachIndexed { i, slot -> slot.isAI = savedAI[i] }
    }

    private fun encodeBody(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        val body = ByteArray(buf.readableBytes())
        buf.readBytes(body)
        return body
    }

    /** 抓包原值: 66.135.19.7 上 4151..4158，每個槽位三組同值。 */
    private fun capturePeers(): List<GameStartPeer> = (0 until MAX_SESSION_SLOTS).map { slot ->
        val endpoint = UdpEndpoint.of(66, 135, 19, 7, 4151 + slot)
        GameStartPeer(slot, List(UDP_PEER_ENDPOINTS) { endpoint })
    }

    @Test
    fun testOpcodeBinding() {
        val sendOp = OpcodeManager.getSendOp(GameStart(peers = emptyList())::class.java)
        assertEquals(136, sendOp, "GameStart send opcode must be 136")
    }

    @Test
    fun testMatchesCaptureBlob() {
        val binFile = File("cap/practice_s2c_op136_178B_0.bin")
        assertTrue(binFile.exists(), "missing capture baseline: ${binFile.absolutePath}")

        val expected = binFile.readBytes()
        val body = encodeBody(
            GameStart(
                clockBaseMs = 1475740900,
                sessionKey = 0,
                timerASec = 180,
                timerBSec = 0,
                tickFlag = 0,
                localEndpoint = UdpEndpoint.of(66, 135, 19, 7, 4151),
                peerTimeoutSec = 1,
                peers = capturePeers(),
            )
        )

        assertEquals(178, expected.size, "抓包基準應為 178B 報文體 (180B 檔含 2B opcode)")
        assertEquals(expected.size, body.size, "包體長度必須與抓包一致")
        assertArrayEquals(expected, body, "GameStart() 必須能逐位元組還原抓包")
    }

    @Test
    fun testSlotTableShape() {
        val body = encodeBody(
            GameStart(
                clockBaseMs = 0,
                timerASec = 40,
                timerBSec = 30,
                localEndpoint = UdpEndpoint.NONE,
                peers = (0 until MAX_SESSION_SLOTS).map { GameStartPeer(it) },
            )
        )
        val buf = Unpooled.wrappedBuffer(body)

        assertEquals(0, buf.Decode4, "clockBaseMs")
        assertEquals(0, buf.Decode4, "sessionKey")
        assertEquals(40, buf.Decode4, "timerASec")
        assertEquals(30, buf.Decode4, "timerBSec")
        assertEquals(0, buf.Decode1, "tickFlag")
        assertEquals(0, buf.Decode4, "localIp")
        assertEquals(0, buf.Decode2, "localPort")
        assertEquals(1, buf.Decode1, "peerTimeoutSec")

        // 客戶端固定讀 8+2 項; 空端點仍要佔 19B (occupied=1 + 3 組 0.0.0.0:0)
        for (slot in 0 until GAME_START_SLOT_ENTRIES) {
            assertEquals(if (slot < MAX_SESSION_SLOTS) 1 else 0, buf.Decode1, "slot $slot occupied")
            if (slot < MAX_SESSION_SLOTS) {
                repeat(UDP_PEER_ENDPOINTS) {
                    assertEquals(0, buf.Decode4, "slot $slot 端點 ip")
                    assertEquals(0, buf.Decode2, "slot $slot 端點 port")
                }
            }
        }
        assertEquals(0, buf.readableBytes(), "槽位表後不得有多餘字節")
        assertEquals(headerBytes + MAX_SESSION_SLOTS * 19 + 2, body.size)
    }

    @Test
    fun testKickedSlotLeavesHole() {
        // 踢掉槽 3 後只剩 7 個參戰槽: 佔用位必須留洞, 不能壓縮重排 (槽號即數組下標)
        val active = listOf(0, 1, 2, 4, 5, 6, 7)
        val body = encodeBody(
            GameStart(peers = active.map { GameStartPeer(it) })
        )
        val buf = Unpooled.wrappedBuffer(body)
        buf.skipBytes(headerBytes)

        val flags = (0 until GAME_START_SLOT_ENTRIES).map {
            val occupied = buf.Decode1
            if (occupied == 1) {
                repeat(UDP_PEER_ENDPOINTS) { buf.Decode4; buf.Decode2 }
            }
            occupied
        }
        assertEquals(listOf(1, 1, 1, 0, 1, 1, 1, 1, 0, 0), flags)
        assertEquals(0, buf.readableBytes())
        assertEquals(headerBytes + 7 * 19 + 3, body.size, "少一個參戰槽剛好少 18B")
    }

    @Test
    fun testStartGameReqSendsGameStartRoster() {
        // 走一次真的 op 92: 回覆鏈第 4 包必須是 op 136, 且名冊與 op 112 的參戰槽一致
        Room.slots.forEachIndexed { i, slot -> slot.isAI = i <= 5 }
        val active = listOf(0, 1, 2, 3, 4, 5)
        val mapId = 176

        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)

        val req = Unpooled.buffer()
        val reqPkt = SendPacketBase(req)
        reqPkt.Encode4(mapId)
        reqPkt.Encode2(29)
        reqPkt.Encode1(0)
        reqPkt.Encode4(mapId)
        reqPkt.Encode1(0)
        reqPkt.Encode1(1) // fileCheckType = 1 (無 CRC)
        reqPkt.Encode4(0) // eventParam
        with(StartGameReq) { req.handle(client) }

        ch.readOutbound<PKT>() // 111 StartGameResult
        ch.readOutbound<PKT>() // 112 GameStartMapInfo
        ch.readOutbound<PKT>() // 100 SetPlayer(isStart=true)
        val pkt136 = ch.readOutbound<PKT>() ?: error("缺少 op 136 GameStart")

        val body = encodeBody(pkt136)
        val buf = Unpooled.wrappedBuffer(body)
        buf.Decode4 // clockBaseMs: 每局取單調時鐘, 不比對
        assertEquals(0, buf.Decode4, "sessionKey: 本模擬器無局內 UDP 通道, 固定 0")
        assertEquals(180, buf.Decode4, "timerASec: 沿用抓包實測的 3 分鐘")
        assertEquals(0, buf.Decode4, "timerBSec: 抓包實測值")
        assertEquals(0, buf.Decode1, "tickFlag")
        assertEquals(0, buf.Decode4, "localIp: 無 UDP 部署填 0")
        assertEquals(0, buf.Decode2, "localPort")
        assertEquals(1, buf.Decode1, "peerTimeoutSec")

        val flags = mutableListOf<Int>()
        for (slot in 0 until GAME_START_SLOT_ENTRIES) {
            val occupied = buf.Decode1
            flags += occupied
            if (occupied == 1) {
                repeat(UDP_PEER_ENDPOINTS) {
                    assertEquals(0, buf.Decode4, "slot $slot 端點 ip 應為 0")
                    assertEquals(0, buf.Decode2, "slot $slot 端點 port 應為 0")
                }
            }
        }
        assertEquals(0, buf.readableBytes(), "包體必須剛好被消費完")
        val expectedFlags = (0 until GAME_START_SLOT_ENTRIES).map { if (it in active) 1 else 0 }
        assertEquals(expectedFlags, flags, "op136 的佔用位必須與 op112 的參戰名單同源")
        assertEquals(headerBytes + active.size * 19 + (GAME_START_SLOT_ENTRIES - active.size), body.size)
        ch.finishAndReleaseAll()
    }
}
