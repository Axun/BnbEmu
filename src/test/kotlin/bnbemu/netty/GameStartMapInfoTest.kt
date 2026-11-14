package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import bnbemu.data.map.MapCatalog
import bnbemu.handler.room.StartGameReq
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.packet.room.assignSpawnPositions
import bnbemu.packet.room.spawnRandom
import bnbemu.server.Room
import bnbemu.server.bomber
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import bnbemu.data.FxdynMapInfo
import java.io.File
import kotlin.random.Random

class GameStartMapInfoTest {

    private lateinit var savedBombers: List<Number>

    /**
     * op112 的 bomber 现在按槽位从 Room.slots 读取（不再写死角色 5），
     * 而抓包基准 cap/practice_s2c_op112_167B_0.bin 里 8 个槽位角色都是 5。
     * 这里显式铺好前置状态并在用例结束后还原，避免依赖进程级单例的默认值。
     */
    @BeforeEach
    fun setUp() {
        savedBombers = Room.slots.map { it.bomber }
        Room.slots.forEach { it.bomber = 5 }
    }

    @AfterEach
    fun tearDown() {
        Room.slots.forEachIndexed { i, slot -> slot.bomber = savedBombers[i] }
    }

    @Test
    fun testGameStartMapInfoOpcodeBinding() {
        val sendOp = OpcodeManager.getSendOp(GameStartMapInfo()::class.java)
        assertEquals(112, sendOp, "GameStartMapInfo send opcode must be 112")
    }

    @Test
    fun testCipherStrRoundTripAndMatchesExpected() {
        val str = "Cemetery04"
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        sendPkt.EncodeCipherStr(str)

        // Encode2(10) -> 0x000A ^ 0xA569 = 0xA563
        val lenBytes = ByteArray(2)
        buf.readBytes(lenBytes)
        assertEquals(0xA5.toByte(), lenBytes[0])
        assertEquals(0x63.toByte(), lenBytes[1])

        // 10 字节加密字串: f3 a7 36 d6 ef b8 36 e1 91 59
        val cipherBytes = ByteArray(10)
        buf.readBytes(cipherBytes)
        val expectedHex = "f3a736d6efb836e19159"
        val actualHex = cipherBytes.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, actualHex, "Encoded cipher string bytes must match 0x75daf0 algorithm")

        // 重新包装解码并断言还原明文
        val fullBuf = Unpooled.buffer()
        SendPacketBase(fullBuf, 3).EncodeCipherStr(str)
        val decoded = fullBuf.DecodeCipherStr
        assertEquals(str, decoded, "DecodeCipherStr must recover the original string")
    }

    @Test
    fun testGameStartMapInfoMatchesCaptureBlob() {
        val binFile = File("cap/practice_s2c_op112_167B_0.bin")
        if (binFile.exists()) {
            val fileBytes = binFile.readBytes()
            val expectedBody = fileBytes.copyOfRange(2, fileBytes.size)

            // 重置 slot 0 角色为默认 5
            Room.slots[0].bomber = 5

            val pkt = GameStartMapInfo()
            val buf = Unpooled.buffer()
            val sendPkt = SendPacketBase(buf, 3)
            pkt.encode(sendPkt)

            val actualBody = ByteArray(buf.readableBytes())
            buf.readBytes(actualBody)

            assertEquals(expectedBody.size, actualBody.size, "Payload size must match 165 bytes")
            assertArrayEquals(expectedBody, actualBody, "GameStartMapInfo() must match capture blob byte-for-byte")
        }
    }

    @Test
    fun testGameStartMapInfoFieldsDecode() {
        Room.slots[0].bomber = 5
        val pkt = GameStartMapInfo(
            randomSeed = 0x7E022E6B,
            themeId = 29,
            mapName = "Cemetery04",
            mapId = 176,
            gameOptions = 15,
            flag = 0
        )
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        val seed = buf.Decode4
        val theme = buf.Decode2
        val name = buf.DecodeCipherStr
        val mapId = buf.Decode4
        val opt = buf.Decode1
        val flag = buf.Decode1
        val slotCount = buf.Decode1

        assertEquals(0x7E022E6B, seed)
        assertEquals(29, theme)
        assertEquals("Cemetery04", name)
        assertEquals(176, mapId)
        assertEquals(15, opt)
        assertEquals(0, flag)
        assertEquals(8, slotCount)

        val expectedSpawns = intArrayOf(2, 1, 6, 0, 3, 4, 5, 7)
        for (i in 0 until slotCount) {
            val idx = buf.Decode1
            val spawn = buf.Decode1
            val bomber = buf.Decode1
            val team = buf.Decode1
            val state = buf.Decode1
            val p1 = buf.Decode4
            val p2 = buf.Decode4
            val p3 = buf.Decode4

            assertEquals(i, idx)
            assertEquals(expectedSpawns[i], spawn)
            assertEquals(5, bomber)
            assertEquals(if (i == 0) 0 else 8, team)
            assertEquals(0, state)
            assertEquals(0, p1)
            assertEquals(0, p2)
            assertEquals(0, p3)
        }

        val obsCount = buf.Decode1
        val f1 = buf.Decode1
        val f2 = buf.Decode1
        val timeLimit = buf.Decode1

        assertEquals(0, obsCount)
        assertEquals(0, f1)
        assertEquals(0, f2)
        assertEquals(0, timeLimit)
        assertEquals(0, buf.readableBytes(), "All bytes must be consumed")
    }

    @Test
    fun testGameStartMapInfoDynamicBomber() {
        // 当玩家更换角色为 2 时
        Room.slots[0].bomber = 2

        val pkt = GameStartMapInfo()
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        buf.Decode4 // seed
        buf.Decode2 // theme
        buf.DecodeCipherStr // name
        buf.Decode4 // mapId
        buf.Decode1 // opt
        buf.Decode1 // flag
        buf.Decode1 // slotCount

        val idx0 = buf.Decode1
        val spawn0 = buf.Decode1
        val bomber0 = buf.Decode1
        assertEquals(0, idx0)
        assertEquals(2, spawn0)
        assertEquals(2, bomber0, "Slot 0 bomber must reflect Room.slots[0].bomber")

        // 还原
        Room.slots[0].bomber = 5
    }

    @Test
    fun testMapRefFollowsFlagZeroOrder() {
        // 协议 mapId = fxdynmapinfo 里 flag==0 记录的 0-based 下标（见 data/map/MapCatalog）
        // 表扩展后下标已重排（176 不再是 Cemetery04），故逐条比对 records 与 refs，
        // 只保留「0 = HotRandom」与越界、缺 .bin 这些与条数无关的不变量。
        val flagZero = FxdynMapInfo.records.filter { it.flag == 0 }
        val refs = MapCatalog.refs
        assertTrue(refs.size > 200, "flag==0 的记录数")
        assertEquals(flagZero.size, refs.size)
        assertEquals("HotRandom", refs[0].name)
        refs.forEachIndexed { i, ref ->
            assertEquals(i, ref.mapId)
            assertEquals(flagZero[i].name, ref.name, "mapId $i 的名字必须与 records 同序")
            assertEquals(flagZero[i].sortOrder, ref.themeId, "mapId $i 的 themeId 即 sort_order")
        }
        val probe = 176
        assertEquals(refs[probe].name, MapCatalog.ref(probe)?.name)
        assertEquals(null, MapCatalog.ref(refs.size), "越界 mapId 不应命中")
        assertEquals(null, MapCatalog.of(0), "HotRandom 没有 .bin，模板应为 null")
    }

    @Test
    fun testStartGameReqDynamicMapName() {
        // 表扩展后 mapId 176 的图名变了，期望值一律从 MapCatalog.ref 派生，
        // 验证的是「handler 用 ref(mapId) 动态取名」而不是某个字符串常量。
        val selectedMapId = 176
        val selectedThemeId = 29
        val expectedName = MapCatalog.ref(selectedMapId)?.name
            ?: error("mapId $selectedMapId 应在 fxdynmapinfo 的 flag==0 表内")

        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)

        // 构造 StartGameReq 载荷 (subType=0, isCustomMap=0, fileCheckType=1, eventParam=0)
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        sendPkt.Encode4(selectedMapId)
        sendPkt.Encode2(selectedThemeId)
        sendPkt.Encode1(0)
        sendPkt.Encode4(selectedMapId)
        sendPkt.Encode1(0)
        sendPkt.Encode1(1) // fileCheckType = 1 (无需 CRC)
        sendPkt.Encode4(0) // eventParam

        with(StartGameReq) {
            buf.handle(client)
        }

        // 伺服器回覆序列: PracticeAiLevel, GameStartMapInfo, PracticeStartInfo, PracticeBoard
        val pkt1 = ch.readOutbound<PKT>() // PracticeAiLevel
        val pkt2 = ch.readOutbound<PKT>() // GameStartMapInfo
        val pkt3 = ch.readOutbound<PKT>() // PracticeStartInfo
        val pkt4 = ch.readOutbound<PKT>() // PracticeBoard

        assertNotNull(pkt2)
        val encBuf = Unpooled.buffer()
        val sPkt = SendPacketBase(encBuf, 3)
        pkt2.encode(sPkt)

        encBuf.Decode4 // seed
        encBuf.Decode2 // theme
        val mapName = encBuf.DecodeCipherStr
        val mapId = encBuf.Decode4
        assertEquals(selectedMapId, mapId)
        assertEquals(expectedName, mapName, "op112 的 mapName 必须来自 MapCatalog.ref(mapId)")
    }

    @Test
    fun testStartGameReqDynamicMapNameFallback() {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)

        // 构造 StartGameReq 载荷，未知 mapId=9999
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        sendPkt.Encode4(9999)
        sendPkt.Encode2(10)
        sendPkt.Encode1(0)
        sendPkt.Encode4(9999)
        sendPkt.Encode1(0)
        sendPkt.Encode1(1)
        sendPkt.Encode4(0)

        with(StartGameReq) {
            buf.handle(client)
        }

        ch.readOutbound<PKT>() // PracticeAiLevel
        val pkt2 = ch.readOutbound<PKT>() // GameStartMapInfo
        assertNotNull(pkt2)

        val encBuf = Unpooled.buffer()
        val sPkt = SendPacketBase(encBuf, 3)
        pkt2.encode(sPkt)

        encBuf.Decode4 // seed
        encBuf.Decode2 // theme
        val mapName = encBuf.DecodeCipherStr
        val mapId = encBuf.Decode4
        assertEquals(9999, mapId)
        assertEquals("Cemetery03", mapName, "Fallback mapName should be Cemetery03")
    }

    @Test
    fun testGameStartMapInfoSkipsKickedAiSlot() {
        // 踢掉 3 號 AI 後, op112 名冊只能有 7 筆, 且槽號/出生點不得壓縮重排。
        val pkt = GameStartMapInfo(activeSlots = listOf(0, 1, 2, 4, 5, 6, 7))
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))

        buf.Decode4 // seed
        buf.Decode2 // theme
        buf.DecodeCipherStr // mapName
        buf.Decode4 // mapId
        buf.Decode1 // gameOptions
        buf.Decode1 // flag

        assertEquals(7, buf.Decode1, "踢掉一個 AI 後 slotCount 必須是 7")
        val expectedSlots = listOf(0, 1, 2, 4, 5, 6, 7)
        val expectedSpawns = listOf(2, 1, 6, 3, 4, 5, 7)
        for (n in expectedSlots.indices) {
            assertEquals(expectedSlots[n], buf.Decode1, "第 $n 筆的槽號")
            assertEquals(expectedSpawns[n], buf.Decode1, "槽 ${expectedSlots[n]} 的出生點")
            buf.Decode1 // bomber
            assertEquals(if (n == 0) 0 else 8, buf.Decode1, "槽 ${expectedSlots[n]} 的隊伍")
            buf.Decode1 // state
            buf.Decode4; buf.Decode4; buf.Decode4
        }
        repeat(4) { buf.Decode1 } // obsCount / field1 / field2 / timeLimit
        assertEquals(0, buf.readableBytes(), "變長名冊後封包必須剛好被消費完")
    }

    @Test
    fun testStartGameReqUsesRoomSlotsAsRoster() {
        // op 92 開局時的名冊要跟著 Room.slots 走, 不能寫死 8 槽。
        val saved = Room.slots.map { it.isAI }
        try {
            Room.slots.forEachIndexed { i, slot -> slot.isAI = i <= 5 }
            val ch = EmbeddedChannel()
            val client = ClientSocket()
            ch.pipeline().addLast(client)

            val buf = Unpooled.buffer()
            val sendPkt = SendPacketBase(buf, 3)
            sendPkt.Encode4(176)
            sendPkt.Encode2(29)
            sendPkt.Encode1(0)
            sendPkt.Encode4(176)
            sendPkt.Encode1(0)
            sendPkt.Encode1(1)
            sendPkt.Encode4(0)
            with(StartGameReq) { buf.handle(client) }

            ch.readOutbound<PKT>() // PracticeAiLevel
            val pkt112 = ch.readOutbound<PKT>() ?: error("缺少 op112 GameStartMapInfo")
            val encBuf = Unpooled.buffer()
            pkt112.encode(SendPacketBase(encBuf, 3))
            encBuf.Decode4; encBuf.Decode2; encBuf.DecodeCipherStr; encBuf.Decode4
            encBuf.Decode1; encBuf.Decode1
            assertEquals(6, encBuf.Decode1, "只有 6 個參戰槽位時 slotCount 必須是 6")

            for (i in 0 until 6) {
                assertEquals(i, encBuf.Decode1)
                encBuf.Decode1; encBuf.Decode1; encBuf.Decode1; encBuf.Decode1
                encBuf.Decode4; encBuf.Decode4; encBuf.Decode4
            }
            repeat(4) { encBuf.Decode1 }
            assertEquals(0, encBuf.readableBytes())
            ch.finishAndReleaseAll()
        } finally {
            Room.slots.forEachIndexed { i, slot -> slot.isAI = saved[i] }
        }
    }

    // ======================================================================
    // 開局出生點隨機化 (2026-11-06)
    // ======================================================================

    @Test
    fun testRandomSpawnPositionsIsPermutationAndVaries() {
        val saved = spawnRandom
        try {
            spawnRandom = Random(20261106)
            // mapId 176 = Forest19（当前表），8 人圖；容量由 MapCatalog 按 .bin 决定
            val draws = List(32) { assignSpawnPositions(176, (0..7).toList()).toList() }
            draws.forEach { spawns ->
                assertEquals((0..7).toList(), spawns.sorted(), "出生點必須是 0..7 的排列: 不重複、不越界")
            }
            assertTrue(draws.distinct().size > 1, "每次開局都必須重新洗牌")
        } finally {
            spawnRandom = saved
        }
    }

    @Test
    fun testSpawnPositionsFollowMapCapacity() {
        val saved = spawnRandom
        try {
            spawnRandom = Random(20261111)
            // mapId 8 = Wriggle01，4 人圖：出生點只能是 0..3（真端 spawns = min(maxPlayer, 8)）
            repeat(16) {
                val spawns = assignSpawnPositions(8, listOf(0, 1, 2, 3))
                assertEquals(listOf(0, 1, 2, 3), spawns.copyOfRange(0, 4).sorted(), "4 人圖不得發 4..7")
            }
            // 只有部分槽位參戰時，其餘槽位保持自身槽號（不會被編碼進 op112）
            val partial = assignSpawnPositions(176, listOf(0, 2, 5))
            assertEquals(8, partial.size)
            listOf(1, 3, 4, 6, 7).forEach { assertEquals(it, partial[it], "未參戰槽 $it 應保持自身槽號") }
            assertEquals(3, listOf(partial[0], partial[2], partial[5]).distinct().size, "參戰槽的出生點互不重複")
        } finally {
            spawnRandom = saved
        }
    }

    @Test
    fun testStartGameReqRollsSpawnPositionsEachRound() {
        val savedRandom = spawnRandom
        val savedAI = Room.slots.map { it.isAI }
        try {
            spawnRandom = Random(112)
            Room.slots.forEach { it.isAI = true } // 固定 8 槽參戰, 方便逐槽比對

            val draws = List(6) { startGameSpawnPositions() }
            draws.forEach { spawns ->
                assertEquals(8, spawns.size, "8 個參戰槽位都要有出生點")
                assertEquals((0..7).toList(), spawns.sorted(), "op112 的出生點不得重複/越界")
            }
            assertTrue(draws.distinct().size > 1, "op 92 每次開局都要重新洗牌出生點")
        } finally {
            Room.slots.forEachIndexed { i, slot -> slot.isAI = savedAI[i] }
            spawnRandom = savedRandom
        }
    }

    /** 走一次真的 op 92 開局流程, 從 op112 解出逐槽出生點。 */
    private fun startGameSpawnPositions(): List<Int> {
        val ch = EmbeddedChannel()
        val client = ClientSocket()
        ch.pipeline().addLast(client)

        val req = Unpooled.buffer()
        val reqPkt = SendPacketBase(req, 3)
        reqPkt.Encode4(176)   // mapId
        reqPkt.Encode2(29)    // themeId
        reqPkt.Encode1(0)     // subType
        reqPkt.Encode4(176)   // roomSelectedMapId
        reqPkt.Encode1(0)     // isCustomMap
        reqPkt.Encode1(1)     // fileCheckType = 1 (無 CRC)
        reqPkt.Encode4(0)     // eventParam
        with(StartGameReq) { req.handle(client) }

        ch.readOutbound<PKT>() // 111 PracticeAiLevel
        val pkt112 = ch.readOutbound<PKT>() ?: error("缺少 op112 GameStartMapInfo")
        val buf = Unpooled.buffer()
        pkt112.encode(SendPacketBase(buf, 3))

        buf.Decode4           // randomSeed
        buf.Decode2           // themeId
        buf.DecodeCipherStr   // mapName
        buf.Decode4           // mapId
        buf.Decode1           // gameOptions
        buf.Decode1           // flag
        val slotCount = buf.Decode1
        val spawns = (0 until slotCount).map {
            buf.Decode1       // slotIdx
            val spawn = buf.Decode1
            repeat(3) { buf.Decode1 } // bomber / team / state
            repeat(3) { buf.Decode4 } // param1..3
            spawn
        }
        ch.finishAndReleaseAll()
        return spawns
    }
}
