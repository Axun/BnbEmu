package pingu.handler

import pingu.netty.Decode1
import pingu.netty.PKTHandler

// 永遠緊跟在 Unk_297 之後發送 (login.pcap / enter-channel.pcap 位置一致),
// payload 僅 [E1(0)], 兩個抓包均無回應 → fire-and-forget, 進頻道的附屬通知
// server = C???::On??? (原始名稱未知, 綁定見 EN_18_recv.yaml Unk_39)
val Unk_39 = PKTHandler { _ ->
    Decode1 // 0
}
