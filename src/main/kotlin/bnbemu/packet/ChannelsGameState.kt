package bnbemu.packet

import bnbemu.netty.PKT

// server = CCenterProcess::MakeChannelsGameStatePacket
// tailOnes: EN 大廳推送變體 (C→S 81 後的握手推送 seq=136) 少一個尾部 E1(1), 傳 2 (9B 載荷);
//           預設 3 = OnConnEstablished 推送版 (seq=49, 10B 載荷)
fun ChannelsGameState(tailOnes: Int = 3) = PKT {
    // enter-channel.pcap +0.305s (op 7 回應序列 #3, 8B):
    // E1(11) E1(4) E1(0) E1(0) E1(0) E1(1) E1(1) E1(1)
    // 11 與 ChangeStage 內的 E2(11) 同值 (大廳階段 stageId 11);
    // 4 對應頻道狀態欄位; 其餘語義未知, 原樣重放
    Encode1(11)
    Encode1(4)
    Encode1(0)
    Encode1(0)
    Encode1(0)
    repeat(tailOnes) { Encode1(1) }
}
