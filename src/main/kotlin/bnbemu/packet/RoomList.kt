package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase

// 房間列表 — op 87 (真・房間列表, 2026-08-30 由房名解密確認; op 72 SessionList 是頻道在線用戶列表)
// 週期推送 (登入期/大廳期都有), 34 個抓包實例皆為單房: E2(1) 恆 1, 多房間時的形態未知
// (可能 count>1 + 多條目, 也可能每房一包 —— 待 live 抓包驗證)
// 條目: E4(f1) E4(f2) E4(f3) E4(f4) E4(名長) 房名(NUL/殘渣補齊到 dword) E4(t1) E4(t2) E4(t3) E1(0)
// 房名加密與 op 72 令牌同款: 明文 4B 一塊 LE 讀 ^ 0x96CA5395 按 BE 寫回
// f1~f4/t1~t3 與 房間編號/上限/目前人數 的對應尚未釘死 (rd 實例值見樣本;
// 下一步在真實伺服器 rd #12 1/1 界面抓包按值匹配)
// server = C???::MakeRoomListPacket (原始名稱未知)
fun RoomList(rooms: List<RoomEntry> = EN_SAMPLE_ROOMS) = PKT {
    Encode2(1)                  // 34 個抓包實例恆 1
    rooms.forEach { it.Encode(this) }
}

// 單一房間條目 (對齊 SessionEntry 的模式); f1~f4/t1~t3 語義未明, 動態生成時先沿用樣本值
class RoomEntry(
    val name: String,           // 房名明文 (客戶端 UI 顯示)
    val f1: Int,
    val f2: Int,
    val f3: Int,
    val f4: Int,
    val namePad: ByteArray = byteArrayOf(0),  // 名字後的 NUL + 伺服器緩存殘渣 (逐字節重放需保留)
    val t1: Int = 0x08000000,
    val t2: Int = 0,
    val t3: Int = 0,
) {
    fun Encode(p: SendPacketBase) {
        p.Encode4(f1)
        p.Encode4(f2)
        p.Encode4(f3)
        p.Encode4(f4)
        val nameBytes = name.toByteArray(ACP)
        p.Encode4(nameBytes.size)
        p.EncodeBuffer(encryptName(nameBytes, namePad))
        p.Encode4(t1)
        p.Encode4(t2)
        p.Encode4(t3)
        p.Encode1(0)            // 尾旗標: rd 實例為 E1(0); 其他實例見過 E2(0)/無, 形態未定
    }

    companion object {
        // 明文 4B 一塊 LE 讀 ^ 0x96CA5395 按 BE 寫回 (解碼方向之逆); 零補齊到 dword
        private fun encryptName(nameBytes: ByteArray, namePad: ByteArray): ByteArray {
            val raw = nameBytes + namePad
            val len = (raw.size + 3) / 4 * 4
            val src = raw.copyOf(len)
            val out = ByteArray(len)
            for (i in 0 until len step 4) {
                val v = (src[i].toInt() and 0xFF) or
                        ((src[i + 1].toInt() and 0xFF) shl 8) or
                        ((src[i + 2].toInt() and 0xFF) shl 16) or
                        ((src[i + 3].toInt() and 0xFF) shl 24)
                val e = v xor 0x96CA5395.toInt()
                out[i] = (e ushr 24).toByte()
                out[i + 1] = (e ushr 16).toByte()
                out[i + 2] = (e ushr 8).toByte()
                out[i + 3] = e.toByte()
            }
            return out
        }
    }
}

// enter-channel.pcap 的 41B 實例 = "rd" 房 (客戶端 UI 顯示的那間) ——
// RoomList() 重編碼須與原包逐字節一致 (replay_test.py enter 用例對 op 87 做逐字節斷言)
private val EN_SAMPLE_ROOMS = listOf(
    RoomEntry(
        name = "rd",
        f1 = 33, f2 = 7192, f3 = 0x3D0000, f4 = 119,
        namePad = byteArrayOf(0x00, 0x90.toByte()),
        t1 = 0x08000000, t2 = 0x04100807, t3 = 0,
    ),
)
