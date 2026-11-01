package pingu.handler

import pingu.netty.Decode1
import pingu.netty.PKTHandler

// 進頻道後客戶端批次請求之一 (enter-channel.pcap: op 7 之後 +0.758s 與 297/310/64/293/78 同批)
// 載荷 = E1(0); 真實伺服器不在收到時立即回應, 而是等批次收齊 (78 為最後一包)
// 才按線序回整套 —— 回應鏈見 StageInitReq (op 78)
// server = C???::On??? (原始名稱未知, 綁定見 EN_18_recv.yaml Unk_302)
val Unk_302 = PKTHandler { _ ->
    Decode1
}
