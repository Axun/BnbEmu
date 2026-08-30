package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// op 7 回應後的週期推送 #2 (seq=61, 26B), 之後進房階段會以相同位元組重複出現
// 內容似隨機 token (每次抓包中同連線內位元組不變) —— 原樣重放
fun Unk423() = PKT {
    EncodeBuffer(EN_UNK423_BLOB)
}

private val EN_UNK423_BLOB = """
    a57bf5a6 36c2b6af 3efac6ea 3ce1f79e 23fa840e 96cadb2d 
""".toBA()
