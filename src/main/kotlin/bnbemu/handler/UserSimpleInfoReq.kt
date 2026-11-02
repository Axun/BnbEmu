package bnbemu.handler

import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler

// 進頻道後客戶端批次請求之一, 載荷 = E2(0); 回應 (13B 版 op 4) 由 StageInitReq (op 78) 鏈按線序發出
// server = C???::OnUserSimpleInfoReq (綁定見 EN_18_recv.yaml UserSimpleInfoReq)
val UserSimpleInfoReq = PKTHandler { _ ->
    Decode2
}
