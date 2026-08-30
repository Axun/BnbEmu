package pingu.packet

import pingu.netty.PKT

// 進頻道批次回應之一 (enter-channel.pcap 線序 seq=79, 6B)
// 僅 E4(0) —— C→S 78 為空載荷
fun Unk78Result() = PKT {
    Encode4(0)
}
