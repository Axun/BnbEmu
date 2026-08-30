package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道批次回應之一 (線序 seq=70, 56B)
// E1(1) + E2(4)+會話串 + E4(0)×n + E2(1) + E4(18) ... 與 op 72 房間列表同窗到達 —— 原樣重放
fun Unk254() = PKT {
    EncodeBuffer(EN_UNK254_BLOB)
}

private val EN_UNK254_BLOB = """
    5ba56df0 ae35f35a 5a96ca53 955a96ca 5395a568 96ca53a7 
    96ca5395 96ca5395 96ca5395 5a96ca53 95a569a5 69a569a5 
    6996ca53 955a     
""".toBA()
