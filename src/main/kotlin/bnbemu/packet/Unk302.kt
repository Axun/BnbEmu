package bnbemu.packet

import bnbemu.netty.PKT

// 進頻道批次回應之一 (enter-channel.pcap 線序 seq=67 與 97, 兩次內容相同, 20B)
// E2(1) + E4(0)×2 + E2(0) + E4(0) + E2(0) —— 與 op 310 回應同構, 語義未明
// (C→S 302 載荷 E1(0); 伺服器在批次收齊後連發兩組 310+302)
fun Unk302() = PKT {
    Encode2(1)
    Encode4(0)
    Encode4(0)
    Encode2(0)
    Encode4(0)
    Encode2(0)
}
