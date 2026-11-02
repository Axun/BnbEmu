package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.toBA

// 進頻道握手推送 #14 (seq=139, 44B) — 與 ResEstimateRTT 同 op 但為大廳推送版, 原樣重放
fun Unk20() = PKT {
    EncodeBuffer(EN_UNK20_BLOB)
}

private val EN_UNK20_BLOB = """
    5ba5685a 96ca5395 5a96ca53 955a5a96 ca539596 ca53955a
    96ca5395 5aa5695a 5aa5695a 5a96ca53 955a5a5a
""".toBA()
