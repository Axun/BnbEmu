package bnbemu.packet

import bnbemu.netty.PKT

// UserSimpleInfoReq (C→S op 64, 載荷 E2(0)) 的回應 (enter-channel.pcap 線序 seq=82, 13B)
// E1(0) E2(0) E1(0)×5 E2(0) E1(0) —— 與 65B 版 op 4 不同, 數值欄位全零 (登入連線的
// 65B 變體帶 E4(1000)/E2(5000) 等值, 見 AskFirstInfoResult / SessionInfoResult)
fun UserSimpleInfoResult() = PKT {
    Encode1(0)
    Encode2(0)
    repeat(5) { Encode1(0) }
    Encode2(0)
    Encode1(0)
}
