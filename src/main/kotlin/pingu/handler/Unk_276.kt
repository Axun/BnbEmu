package pingu.handler

import pingu.netty.PKTHandler

// 進頻道握手批次之末 (C→S 81 → 274×2 → 281 → 276, 見 Unk_274.kt / JoinSessionReq.kt), 空載荷空消費;
// 客戶端在推送 [204..423] 之後才發它, 是握手的最後一包, 之後只剩 op 15 輪詢
// server = C???::On??? (原始名稱未知, 綁定見 EN_0_recv.yaml Unk_276)
val Unk_276 = PKTHandler { _ ->
}
