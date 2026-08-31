package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #7 (seq=118, 20B) — 原樣重放
fun Unk212() = PKT {
    EncodeBuffer(EN_UNK212_BLOB)
}

private val EN_UNK212_BLOB = """
    a004b612 5aa57f96 ca539452 5a5b5859 5e5c5253
""".toBA()
