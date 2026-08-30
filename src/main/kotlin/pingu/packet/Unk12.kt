package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// EN 頻道進入推送序列第 3 包 (login.pcap sq=52, 緊跟 ChannelsState 之後)
// SSD 資料塊 (21B): 用 0x96CA5395 解碼 = 同族合法空塊 (2026-08-31 確認):
//   [0x18][(21-5)&0xFF=0x10 ✓塊頭規律][u32 0][u16 條目數=0] + 12B 全零 + Encode1(0) 原樣尾
// 條目數 0 → 無內容可解; 12B 全零區角色未知 (可能是 op12 特有的定長欄位, 需非零樣本才能切分)。
// 全部三個 pcap 僅此一個 op12 樣本 (27B 載荷; enter-channel 登錄連線尾巴僅 3×op86+op7, 無 op12)
fun Unk12() = PKT {
    Encode4(21)
    EncodeBuffer(EN_UNK12_BLOB)
}

private val EN_UNK12_BLOB = """
    96ca438d 96ca5395 96ca5395 96ca5395 96ca5395 5a
""".toBA()
