package bnbemu.packet

import bnbemu.netty.PKT

// server = CChannelCache::MakeInfoPacket
fun ChannelsInfo() = PKT {
    Encode1Bool()
    // TODO
}