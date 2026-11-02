package bnbemu.packet

import bnbemu.Ver
import bnbemu.isCN
import bnbemu.isEN
import bnbemu.netty.PKT

val availableChannelSize = 1
val channelVer = if (isCN && Ver == 12) 24 else Ver // 2003 ~ 2004裡 客戶端被分成兩個(Cx/Fx) 各自有各自的版本

// server = CCenterProcess::MakeChannelsGameStatePacket
// tailOnes: EN 大廳推送變體 (C→S 81 後的握手推送 seq=136) 少一個尾部 E1(1), 傳 2 (9B 載荷);
//           預設 3 = OnConnEstablished 推送版 (seq=49, 10B 載荷)
fun ChannelsGameState(tailOnes: Int = 3) = PKT {
    if (isEN) {
        // enter-channel.pcap +0.305s (op 7 回應序列 #3, 8B):
        // E1(11) E1(4) E1(0) E1(0) E1(0) E1(1) E1(1) E1(1)
        // 11 與 ChangeStage 內的 E2(11) 同值 (大廳階段 stageId 11; TW 版此處為 Encode1(10)=ChannelID);
        // 4 與 TW 版的 State 欄位位置對應; 其餘語義未知, 原樣重放
        Encode1(11)
        Encode1(4)
        Encode1(0)
        Encode1(0)
        Encode1(0)
        repeat(tailOnes) { Encode1(1) }
    } else {
        Encode1(availableChannelSize)

        repeat(availableChannelSize) { i ->
            Encode1(10) // ChannelID
            Encode1(1) // State 0 = 服務中斷
            Encode2(channelVer)
            Encode2(3)
        }
    }
}
