package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import bnbemu.packet.room.MapEventEntry
import bnbemu.packet.room.MapEventSync

// S→C op 172 = MapEventSync (地图事件/波时间轴同步), 不是 ItemThrow。
// 基准载荷 = tst.pcap stream4 sq=343 的 body (去掉 2B opcode a5 c5):
//   a5 d9 a5 74 5b 96 ca 52 04 5b 5a a5 69 5a 5a
//   → E2(sessionA=176) E2(sessionB=29) E1(eventId=1) E4(timeMs=401) E1(count=1)
//     + E1(0) E2(0) E1(0) E1(0)
class MapEventSyncTest {

    @Test
    fun testOpcodeBinding() {
        val op = OpcodeManager.getSendOp(
            MapEventSync(sessionA = 176, sessionB = 29, eventId = 1, timeMs = 401)::class.java
        )
        assertEquals(172, op, "MapEventSync send opcode must be 172")
    }

    @Test
    fun testEncodingMatchesCapture() {
        val pkt = MapEventSync(
            sessionA = 176,
            sessionB = 29,
            eventId = 1,
            timeMs = 401,
            entries = listOf(MapEventEntry())
        )
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))

        val actual = ByteArray(buf.readableBytes())
        buf.readBytes(actual)

        val expected = byteArrayOf(
            0xA5.toByte(), 0xD9.toByte(), // E2(176) sessionA
            0xA5.toByte(), 0x74.toByte(), // E2(29)  sessionB
            0x5B.toByte(),                // E1(1)   eventId
            0x96.toByte(), 0xCA.toByte(), 0x52.toByte(), 0x04.toByte(), // E4(401) timeMs
            0x5B.toByte(),                // E1(1)   count
            0x5A.toByte(),                // E1(0)   a
            0xA5.toByte(), 0x69.toByte(), // E2(0)   b
            0x5A.toByte(),                // E1(0)   c
            0x5A.toByte()                 // E1(0)   d
        )
        assertEquals(
            expected.toHexString(), actual.toHexString(),
            "MapEventSync(176, 29, 1, 401, [0,0,0,0]) 必须与抓包 body 逐字节一致"
        )
    }

    @Test
    fun testEntryCountDrivesLength() {
        val buf = Unpooled.buffer()
        MapEventSync(
            sessionA = 176, sessionB = 29, eventId = 1, timeMs = 5203,
            entries = listOf(MapEventEntry(d = 5), MapEventEntry(d = 6), MapEventEntry(d = 7))
        ).encode(SendPacketBase(buf, 3))

        // 头部 10B + 3 × 5B 条目
        assertEquals(25, buf.readableBytes(), "count 字段必须等于条目数并决定包长")
    }
}
