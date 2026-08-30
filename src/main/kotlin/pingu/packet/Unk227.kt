package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道批次回應之一 (線序 seq=88, 37B)
// e31372f6(4B) + 會話串 + 數值, 與 op 261 內前綴相同 —— 原樣重放
fun Unk227() = PKT {
    EncodeBuffer(EN_UNK227_BLOB)
}

private val EN_UNK227_BLOB = """
    5be31372 f696ca53 9596ca53 95a56df0 ae35f35a 96ca5394 
    5a96ca4f baa56df0 ae35f3   
""".toBA()
