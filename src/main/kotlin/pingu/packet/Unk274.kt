package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #4/#5 (seq=109/112, 15B ×2 連發; 兩變體僅第 3 字節 E1(0/1) 之差) — 原樣重放
fun Unk274(v: Int = 0) = PKT {
    EncodeBuffer(if (v == 0) EN_UNK274_0_BLOB else EN_UNK274_1_BLOB)
}

private val EN_UNK274_0_BLOB = """
    5a96ca53 9496ca53 9596ca53 955d5a
""".toBA()

private val EN_UNK274_1_BLOB = """
    5b96ca53 9496ca53 9596ca53 955d5a
""".toBA()
