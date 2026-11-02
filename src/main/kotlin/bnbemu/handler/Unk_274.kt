package bnbemu.handler

import bnbemu.netty.PKTHandler

// 進頻道握手批次: 客戶端在「進頻道握手推送」(C→S 81 的回應鏈) 收完後跟發 274(13B)×2 + 281(空) + 276(空)
// (enter-channel.pcap seq=64/67/70/73, 與 81 同屬進頻道握手流程; 真實伺服器無逐包回應,
//  後續的 87/65 與 204/66/14/20/261/247/423 推送為伺服器主動跟發, 見 JoinSessionReq.kt)
// server = C???::On??? (原始名稱未知, 綁定見 EN_18_recv.yaml Unk_274)
val Unk_274 = PKTHandler { _ ->
}
