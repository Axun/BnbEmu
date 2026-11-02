package bnbemu.handler

import bnbemu.netty.PKTHandler

// 進頻道後客戶端批次請求之一, 空載荷; 回應由 StageInitReq (op 78) 鏈按線序發出 (見 Unk_302 註釋)
// server = C???::On??? (原始名稱未知, 綁定見 EN_18_recv.yaml Unk_293)
val Unk_293 = PKTHandler { _ ->
}
