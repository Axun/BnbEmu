package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.toBA

// 進頻道握手推送 #16 (seq=145, 4B) — 原樣重放
fun Unk247() = PKT {
    EncodeBuffer(EN_UNK247_BLOB)
}

private val EN_UNK247_BLOB = """
    5b5aa569
""".toBA()
