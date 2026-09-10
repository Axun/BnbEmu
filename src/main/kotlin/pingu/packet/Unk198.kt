package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #2 (seq=103, 135B) — 原樣重放
// 結構 (2026-08-31 解): 载荷 = E1(2) + 兩條逐字節相同的 67B 記錄 (record[0:67] == record[67:134])。
//   記錄內無任何可讀 ASCII / 會話串令牌 (f0ae35f3 不在內)、非 SSD 塊 (無 0x18 頭)、
//   非 RoomList/SessionList/名冊; 首 4B 按 SSD 式解讀 = dword 2, dword 1,
//   尾部 18B 重複模式 8895ae63d62cc49196c9fa15 與 op413/op200/op65 共享 (同進頻道資源塊家族)。
//   疑似商店/道具/形象數據塊 (2 條相同記錄), 無第二份抓包樣本可 diff, 語義未釘死。
fun Unk198() = PKT {
    EncodeBuffer(EN_UNK198_BLOB)
}

private val EN_UNK198_BLOB = """
    5896ca53 9796ca53 945596ca 5381a56a c42d3588 95ae63d6
    2cc49196 c9fa159a 96ca7483 5796ca53 94a565fb af27dcf3
    be1abaf1 ab11f888 95ae63d6 2cc49196 c9fa15a5 96ca5397
    96ca5394 5596ca53 81a56ac4 2d358895 ae63d62c c49196c9
    fa159a96 ca748357 96ca5394 a565fbaf 27dcf3be 1abaf1ab
    11f88895 ae63d62c c49196c9 fa15a5
""".toBA()
