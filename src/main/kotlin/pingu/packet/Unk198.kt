package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #2 (seq=103, 135B) — 原樣重放
fun Unk198() = PKT {
    EncodeBuffer(EN_UNK198_BLOB)
}

private val EN_UNK198_BLOB = """
    5896ca53 9796ca53 945596ca 5381a56a c42d3588 95ae63d6
    2cc49196 c9fa159a 96ca7483 5796ca53 94a565fb af27dcf3
    be1abaf1 ab11f888 95ae63d6 2cc49196 c9fa15a5 96ca5397
    96ca5394 5596ca53 81a56ac4 2d358895 ae63d62c c49196c9
    fa159a96 ca748357 96ca5394 a565fbaf 27dcf3be 1abaf1ab
    11f88895 ae63d62c c49196c9 fa15a5
""".toBA()
