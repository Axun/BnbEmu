package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler

import bnbemu.packet.ResConnectToSvr

// 選擇頻道后发送。真實抓包請求 = E1(10) + E4(2), live 客戶端發 E4(0)
// server = CLoginPool::OnReqConnectToSvr
val ReqConnectToSvr = PKTHandler { c ->
    val channelId = Decode1 // 频道号，只存不校验
    // 同一频道号在频道列表内的第几个条目（0-based 子序号），一定要原样回显
    val idx = Decode4

    c send ResConnectToSvr(true, channelId, idx)
}