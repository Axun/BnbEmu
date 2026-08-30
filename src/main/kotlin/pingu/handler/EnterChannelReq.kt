package pingu.handler

import pingu.netty.Decode4
import pingu.netty.PKTHandler
import pingu.packet.ChannelsExtraInfo
import pingu.packet.ChannelsState

// 進頻道請求 (EN 真實 opcode 為 11, 舊 TW_5 表的 EnterChannelStageReq=9 不適用)
// login.pcap +0.570s: C 發 op 11 (4B 頻道 hash) → S 回 [11 ChannelsExtraInfo, 13 ChannelsState]
// (login.pcap 中 [11,13,12] 已由 Unk_55 提前推送, 故此處只補 [11,13])
// server = CCenterProcess::OnEnterChannel
val EnterChannelReq = PKTHandler { c ->
    Decode4 // 頻道 hash
    c.send(
        ChannelsExtraInfo(),
        ChannelsState(),
    )
}
