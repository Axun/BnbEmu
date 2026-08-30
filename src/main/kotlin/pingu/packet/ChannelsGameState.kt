package pingu.packet

import pingu.Ver
import pingu.isCN
import pingu.isEN
import pingu.netty.PKT

val availableChannelSize = 1
val channelVer = if (isCN && Ver == 12) 24 else Ver // 2003 ~ 2004裡 客戶端被分成兩個(Cx/Fx) 各自有各自的版本

// server = CCenterProcess::MakeChannelsGameStatePacket
fun ChannelsGameState() = PKT {
    if (isEN) {
        // enter-channel.pcap +0.305s (op 7 回應序列 #3, 8B):
        // E1(11) E1(4) E1(0) E1(0) E1(0) E1(1) E1(1) E1(1)
        // 11 與 Unk97 內的 E2(11) 同值 (推測頻道標識; TW 版此處為 Encode1(10)=ChannelID);
        // 4 與 TW 版的 State 欄位位置對應; 其餘語義未知, 原樣重放
        Encode1(11)
        Encode1(4)
        Encode1(0)
        Encode1(0)
        Encode1(0)
        Encode1(1)
        Encode1(1)
        Encode1(1)
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
