package bnbemu.handler

import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler

import bnbemu.packet.ChannelsExtraInfo
import bnbemu.packet.ChannelsGameState
import bnbemu.packet.ChannelsInfo
import bnbemu.packet.ChannelsState

// server = RequestEnterChannelStage
val EnterChannelStageReq = PKTHandler { c ->
    val ChannelCache_HashValue = Decode4

    c.send(
        ChannelsInfo(),
        ChannelsState(),
        ChannelsGameState(),
        ChannelsExtraInfo()
    )
}