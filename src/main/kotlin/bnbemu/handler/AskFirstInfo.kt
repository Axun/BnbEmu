package bnbemu.handler

import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.AskFirstInfoResult

// server = CLoginPool::OnAskFirstInfo
// EN: 回應 op 4 AskFirstInfoResult (65B), 真實伺服器不回 FirstInfoInit
// (1861B 的 FirstInfoInit 是首連上的主動推送, 見 AskFirstSession.kt)
val AskFirstInfo = PKTHandler { c ->
    Decode4 // 之後 36B 為 passport 票據, 模擬器不驗證, 跳過
    c send AskFirstInfoResult()
}
