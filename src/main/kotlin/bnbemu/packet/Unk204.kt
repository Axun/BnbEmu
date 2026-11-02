package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.toBA

// 進頻道握手推送 #11 (seq=130, 12B) — 原樣重放
fun Unk204() = PKT {
    EncodeBuffer(EN_UNK204_BLOB)
}

private val EN_UNK204_BLOB = """
    96ca539d 96ca508d 96ca5395
""".toBA()
