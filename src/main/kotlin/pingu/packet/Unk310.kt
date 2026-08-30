package pingu.packet

import pingu.netty.PKT

// 進頻道批次回應之一 (enter-channel.pcap 線序 seq=64 與 94, 兩次內容相同, 20B)
// E2(1) + E4(0)×2 + E2(0) + E4(0) + E2(0) —— 與 op 302 回應同構, 語義未明
// (C→S 310 為空載荷; 伺服器在批次收齊後連發兩組 310+302)
fun Unk310() = PKT {
    Encode2(1)
    Encode4(0)
    Encode4(0)
    Encode2(0)
    Encode4(0)
    Encode2(0)
}
