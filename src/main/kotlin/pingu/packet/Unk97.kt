package pingu.packet

import pingu.netty.PKT

// op 7 OnConnEstablished 回應序列 #4 (enter-channel.pcap +0.305s)
// payload = [E2(11)]: 11 與 ChannelsGameState EN 體內的 ChannelID 同值, 推測為頻道標識
fun Unk97() = PKT {
    Encode2(11)
}
