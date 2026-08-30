package pingu.handler

import pingu.netty.PKTHandler
import pingu.packet.ChannelsExtraInfo
import pingu.packet.ChannelsState
import pingu.packet.Unk12

// 登入後客戶端自動跟發的請求 (login.pcap +0.304s, op 2 之後 ~230ms, 12B payload 語義未知)
// 真實伺服器回應頻道列表推送 [11 ChannelsExtraInfo, 13 ChannelsState, 12 Unk12]
// 客戶端據此顯示頻道(伺服器)列表; 缺少它登入後無法進大廳
// server = C???Pool::On??? (原始名稱未知, 綁定見 EN_0_recv.yaml Unk_55)
val Unk_55 = PKTHandler { c ->
    c.send(
        ChannelsExtraInfo(),
        ChannelsState(),
        Unk12(),
    )
}
