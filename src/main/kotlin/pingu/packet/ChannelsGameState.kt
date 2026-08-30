package pingu.packet

import pingu.Ver
import pingu.isCN
import pingu.netty.PKT

val availableChannelSize = 1
val channelVer = if (isCN && Ver == 12) 24 else Ver // 2003 ~ 2004裡 客戶端被分成兩個(Cx/Fx) 各自有各自的版本

// server = CCenterProcess::MakeChannelsGameStatePacket
fun ChannelsGameState() = PKT {
    Encode1(availableChannelSize)

    repeat(availableChannelSize) { i ->
        Encode1(10) // ChannelID
        Encode1(1) // State 0 = 服務中斷
        Encode2(channelVer)
        Encode2(3)
    }
}