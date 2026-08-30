package pingu.packet

import pingu.netty.PKT

// op 7 OnConnEstablished 回應序列 #5 (enter-channel.pcap 線序 seq=55, 7B)
// E1(0) + E4(5000) —— AGENTS.md: 疑似 5 秒定時器 (大廳期週期性重複出現)
fun Unk122() = PKT {
    Encode1(0)
    Encode4(5000)
}
