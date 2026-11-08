package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.netty.ssdEncode

// 房間列表 — op 87 (真・房間列表, 2026-08-30 由房名解密確認; op 72 SessionList 是頻道在線用戶列表)
// 週期推送 (登入期/大廳期都有), 34 個抓包實例皆為單房: E2(1) 恆 1, 多房間時的形態未知
// (可能 count>1 + 多條目, 也可能每房一包 —— 待 live 抓包驗證)
// 條目: E4(f1) E4(f2) E4(f3) E4(f4) E4(名長) 房名(NUL/殘渣補齊到 dword) E4(t1) E4(t2) E4(t3) E1(0)
// 房名加密 = ssdEncode (與 op 72 令牌同款), 但房名須先零補齊到 dword 對齊再編碼
// f1~f4/t1~t3 與 房間編號/上限/目前人數 的對應尚未釘死 (rd 實例值見樣本;
// 下一步在真實伺服器 rd #12 1/1 界面抓包按值匹配)
// server = C???::MakeRoomListPacket (原始名稱未知)
fun RoomList(rooms: List<RoomEntry> = EN_SAMPLE_ROOMS) = PKT {
    Encode2(1)                  // 34 個抓包實例恆 1
    rooms.forEach { it.Encode(this) }
}

// 單一房間條目 (對齊 SessionEntry 的模式); f1~f4/t1~t3 語義未明, 動態生成時先沿用樣本值
// ⚠️ 2026-11-08 補: f1 (33 / tst 那筆 31) 其實等於「後續整段的長度」——
//   客戶端 op 87 處理器 0x0050FF27 先用 E2 讀筆數, 再用 0x0078F1E0 讀
//   「E4 長度 + 該長度的子流」(子流內容是 SSD 變換後的位元組, 由 0x0078BE30/0x0078BE60 以
//   原生 WORD/DWORD 讀出房號與會話句柄)。所以 f1 不是房間欄位, 而是整個條目的長度前綴,
//   一旦這裡要做多房/改名, 必須按「E4(剩餘長度) + 剩餘」重算, 不能沿用 33。
//   另: 條目裡的房號 (f3 >> 16, rd = 61 / tst 練習房 = 75) 就是 S→C op 83 的 sessionId,
//   客戶端靠它把房間資訊填進 CFxContext; 目前 op 83 用 Room.sessionId (=75) 而這份樣本房號是 61,
//   所以客戶端找不到房、只走「進房成功」分支 (不影響流程) —— 兩者要在 op 87 結構化時統一。
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
        // 房名 + NUL/緩存殘渣後零補齊到 4B 倍數: 客戶端按 dword 讀房名, 這個對齊不能省。
        // 補齊後長度必為 4B 倍數, ssdEncode 只走 dword 分支, 與原本手寫迴圈逐位元組一致。
        private fun encryptName(nameBytes: ByteArray, namePad: ByteArray): ByteArray {
            val raw = nameBytes + namePad
            return ssdEncode(raw.copyOf((raw.size + 3) / 4 * 4))
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
