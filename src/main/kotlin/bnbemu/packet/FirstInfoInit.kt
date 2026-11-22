package bnbemu.packet

import bnbemu.data.CsysStr
import bnbemu.netty.PKT

// server = CLoginPool::OnAskFirstInfo
// EN 真實伺服器在首連 (會話連線) 上主動推送, 不等客戶端請求 (start.pcap sq=46)
// 結構: Encode1Bool(true) + Encode4(1854) + 1854B 資料塊
// 資料塊 = csysstr.ssd 快取體 (CStringMan 的 key→文案字典, 38 條):
//   [0x18][u32 1849][compress=0] + plain(1848) = u16 條數 + 38×(u32 key | u32 value)
// 內容與序列化全部由 data/CsysStr.kt 定義, 本檔只負責三個欄位, 不再有 hex 常量。
// 客戶端處理鏈: sub_682B7C(:556772) 首連握手固定等 193→2→67→1→51 →
//   sub_6C406B(:615262) 收包 → sub_6C3ED6(:615187) 解析成 CStringMan 字典。
fun FirstInfoInit() = PKT {
    Encode1Bool(true)                    // hasNew = 1: 後面跟資料塊 (0 = 沿用本地 csysstr.ssd)
    Encode4(CsysStr.wireBlock.size)      // 塊長 = 1854
    EncodeBuffer(CsysStr.wireBlock)      // ssdEncode(buildSsdBlock(plain, compress = false))
}
