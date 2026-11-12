package bnbemu.packet

import bnbemu.data.FxdynMapInfo
import bnbemu.netty.PKT

// ============================================================================
// S→C op 212: 特效與角色可用性配置 (進頻道握手推送 #7, seq=118, 20B)
// ============================================================================
// 反編譯確認 CA.exe.c + F:/Games/PopTag/CA.exe 二進位 xref 交叉核對。
// handler 共兩份、讀取順序完全一致：0x004FE5D5 (CStage::OnPacket, CA.exe.c:216374)、
// 0x00639944 (CSquareStage::OnPacket, CA.exe.c:490183)。
//
// [E4] fxDynMapChecksum ── fxdynmapinfo 資料版本/校驗戳（不是亂數種子）
//   sub_532337(dword_993CD8, v) → 寫入 CEffectMan/CDynamicMapMan +0x24（CA.exe.c:258183）
//   → sub_532209 立刻開 "fxdynmapinfo.ssd"（CA.exe.c:258119）
//   → sub_531F79 只有 `sub_78BB50(stream) == this+9` 時才解析記錄表（CA.exe.c:258038）。
//   sub_78BB50 = COSmartStream +0x14，由 sub_78BB60 對 SSD 塊 (a2+5, len) 算
//   Adler-32 變體（a/b 初值 0，見 CA.exe.c:747998）；未壓縮塊前導 compress byte = 0，
//   不影響校驗值。抓包 0x36CEE587 已用 cap/fxdynmapinfo_plain.bin（24111B 明文記錄表）
//   復算一致 ⇒ 它就是 op 210 下發內容的校驗戳，用來判斷本地快取是否為當前版本。
//   2026-11-12 起本值由 FxdynMapInfo.checksum 對「同一份 op210 明文」即時算出
//   （data/FxdynMapInfo.kt: ssdStreamChecksum），表內容變動時自動同步，不會再脫節；
//   當前擴充表 108601B → 0xF041AFE3，抓包基準 0x36CEE587 只對應舊 342 條記錄。
//
// [E1] highLevelMacroDetect ── 反外掛「高階巨集/連點偵測」開關或等級
//   sub_7AF110(dword_9F10A8, v) → CHighLevelMacroDetector 單例 +0x10（CA.exe.c:780075）；
//   建構 sub_7AF520 預設 0，getter 為 sub_7AF120。二進位掃描：setter 僅被上述兩個
//   op212 handler 呼叫（0x4FE601 / 0x63996C），getter 與 vtable 皆無任何 xref
//   ⇒ 客戶端主程式根本不讀它，抓包值 0 = 關閉/預設。
//
// [E2] reservedU16 (22) → dword_9F0FF8；[E4] reservedU32 (1) → dword_9F0FFC
//   二進位 xref 各只有 2 條 `mov [abs], eax`（0x4FE613 / 0x63997E、0x4FE61F / 0x63998A），
//   CA.exe.c 全檔無讀取、全客戶端目錄也沒有其它模組以這兩個絕對位址引用
//   ⇒ 語意無法從反編譯確定；只能判定為服務端下發、客戶端存而不用的保留純量，
//     保持抓包常數即可，改值不影響客戶端行為（只要包長正確）。
//
// [E1] count → sub_6874FC(CFxContext, count)，經 sub_44B4D1 以混淆 byte 存入 +0x630
// [E1×N] characters → sub_686323(CFxContext) 取 +0x628 的 std::list<int>，
//   sub_4F9669 清空後逐筆 sub_4FD973 push_back；sub_6872E1 以「ID 是否在表內」
//   判斷角色可用性（0~9 基礎角色直接處理、10~13 需查表、14/16 另判），
//   呼叫點見 CA.exe.c:143771 / 150738~150746 / 159076 / 231058 / 236536。
//
fun FxConfig(
    fxDynMapChecksum: Int = FxdynMapInfo.checksum,
    highLevelMacroDetect: Int = 0,
    reservedU16: Int = 22,
    reservedU32: Int = 1,
    characters: List<Int> = listOf(0, 1, 2, 3, 4, 6, 8, 9)
) = PKT {
    Encode4(fxDynMapChecksum)
    Encode1(highLevelMacroDetect)
    Encode2(reservedU16)
    Encode4(reservedU32)
    Encode1(characters.size)
    for (charId in characters) {
        Encode1(charId)
    }
}
