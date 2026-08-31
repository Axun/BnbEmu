package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #15 (seq=142, 5B) — 原樣重放
fun Unk261() = PKT {
    EncodeBuffer(EN_UNK261_BLOB)
}

private val EN_UNK261_BLOB = """
    5be31372 f6
""".toBA()
