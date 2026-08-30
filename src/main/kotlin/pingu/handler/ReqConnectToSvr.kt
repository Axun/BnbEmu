package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.PKTHandler

import pingu.packet.ResConnectToSvr

// 選擇頻道
// server = CLoginPool::OnReqConnectToSvr
val ReqConnectToSvr = PKTHandler { c ->
    val ChannelID = Decode1 // 10
    // 真實抓包請求 = E1(10) + E4(2), live 客戶端發 E4(0), 含義不明 —— 回應中原樣回顯
    val flags = Decode4

//    if (ChannelID != 11) { // 11 = 商城
//        val ClientVer = Decode2 // 5
//    }

    val res = 1
    c send ResConnectToSvr(res, ChannelID, flags)
}