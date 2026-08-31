package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #12 (seq=133, 12B) — 原樣重放
fun Unk66() = PKT {
    EncodeBuffer(EN_UNK66_BLOB)
}

private val EN_UNK66_BLOB = """
    96ca539d 96ca508d 96ca5395
""".toBA()
