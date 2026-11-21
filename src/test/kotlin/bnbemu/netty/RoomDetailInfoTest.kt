package bnbemu.netty

import bnbemu.packet.lobby.RoomDetailSlot
import bnbemu.packet.lobby.RoomDetailInfo
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * C→S op 67 房間詳情請求 / S→C **op 85** 回應的註冊與佈局回歸。
 *
 * 請求實包 (debug 日誌) = A5 54 96 CA 53 E2 → E2(房號 61) + E4(序號 119),
 * 與 op 87 RoomList 的 rd 房 f3>>16 / f4 逐值吻合, 見 handler/RoomDetailInfoReq.kt 註釋。
 *
 * ⚠️ 2026-11-15 實測更正: 回應不是 op 84 (那是 JoinFreeSessionResult, 會直接進房間),
 *    而是 case 'U' (0x55) → sub_50702E 的 op 85, 且 body 沒有房號/序號/房型欄位。
 */
class RoomDetailInfoTest {

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    @Test
    fun `op 67 綁定新房間詳情 Handler 且 op 85 註冊為 RoomDetailInfo`() {
        assertEquals("RoomDetailInfoReq", OpcodeManager.recvOps[67], "舊名 UserInfoDetailReq 已註解")
        assertNotNull(handlerArray[67], "handler 必須被 OpcodeManager 掃到並註冊")

        val pkt = RoomDetailInfo(slots = emptyList())
        assertEquals(85, OpcodeManager.getSendOp(pkt.javaClass), "filteredName 不能含底線, 須命中 yaml 鍵")
    }

    @Test
    fun `空房間 body = E1 有效旗標 + 8 空槽 + E4 剩餘時間`() {
        val actual = encode(RoomDetailInfo(slots = emptyList()))
        val expected = byteArrayOf(
            0x5B,                                                  // E1(1) 房間資料有效
            0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A, // 8 個空槽
            0x96.toByte(), 0xCA.toByte(), 0x53, 0x95.toByte(), // E4(0) 剩餘時間
        )
        assertArrayEquals(expected, actual)
        assertEquals(13, actual.size)
    }

    @Test
    fun `佔用槽位帶令牌化角色名與等級房主欄位`() {
        val actual = encode(
            RoomDetailInfo(slots = listOf(RoomDetailSlot(name = "ffdf", level = 5, chief = 1)))
        )
        // 槽 0: E1(1) E1(0) E2(4) + 令牌 f0ae35f3 ("ffdf") + E2(5) E1(0) E1(1)
        val slot0 = byteArrayOf(
            0x5B, 0x5A, 0xA5.toByte(), 0x6D,
            0xF0.toByte(), 0xAE.toByte(), 0x35, 0xF3.toByte(),
            0xA5.toByte(), 0x6C, 0x5A, 0x5B,
        )
        val tail = ByteArray(7) { 0x5A } + byteArrayOf(0x96.toByte(), 0xCA.toByte(), 0x53, 0x95.toByte())
        assertArrayEquals(byteArrayOf(0x5B) + slot0 + tail, actual)
    }

    @Test
    fun `valid 為 0 時只發 1 位元組`() {
        // 客戶端 sub_50702E 的 else 分支彈 "NotValidRoomInfo", 不再讀任何欄位
        assertArrayEquals(byteArrayOf(0x5A), encode(RoomDetailInfo(slots = emptyList(), valid = 0)))
    }
}
