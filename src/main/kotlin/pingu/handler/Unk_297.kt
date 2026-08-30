package pingu.handler

import pingu.netty.Decode1
import pingu.netty.DecodeStr
import pingu.netty.PKTHandler

// 進頻道上下文自動跟發 (login.pcap 進頻道後 +0.570s / enter-channel.pcap op 7 之後 +0.523s)
// payload = [E2(4)]["f0ae35f3"][E1(1)]: 4B 串與 op 4 AskFirstInfoResult / op 254 內的完全相同,
// 即本用戶的 4B 會話令牌; 兩個抓包中伺服器都「從不回應」→ fire-and-forget
// 推測作用: 向頻道登記自己的會話令牌 (好友/邀請/P2P 定位用, NMCO 體系)
// 綁定空 handler 以對齊真實行為 (只消費欄位, 不回包, 消除 "找不到 Handler" 噪音)
// server = C???::On??? (原始名稱未知, 綁定見 EN_0_recv.yaml Unk_297)
val Unk_297 = PKTHandler { _ ->
    DecodeStr // E2(4) + 4B 會話令牌
    Decode1   // 1
}
