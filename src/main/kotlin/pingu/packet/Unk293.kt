package pingu.packet

import pingu.netty.PKT

// 進頻道批次回應之一 (enter-channel.pcap 線序 seq=76, 3B)
// 僅 E1(0) —— C→S 293 為空載荷
fun Unk293() = PKT {
    Encode1(0)
}
