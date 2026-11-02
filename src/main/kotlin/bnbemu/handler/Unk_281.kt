package bnbemu.handler

import bnbemu.netty.PKTHandler

// 進頻道握手批次之一 (C→S 81 → 274×2 → 281 → 276, 見 Unk_274.kt / JoinSessionReq.kt), 空載荷空消費
// server = C???::On??? (原始名稱未知, 綁定見 EN_18_recv.yaml Unk_281)
val Unk_281 = PKTHandler { _ ->
}
