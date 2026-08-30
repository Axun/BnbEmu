package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 頻道連線內的 65B op 4 (enter-channel.pcap 線序 seq=85, 批次回應之一)
// 與登入連線的 AskFirstInfoResult 同 opcode 但內容不同: 數值欄位全為 0,
// 僅保留 E1(1) 與 4B 會話串 —— 原樣重放
fun SessionInfoResult() = PKT {
    EncodeBuffer(EN_SESSION_INFO_RESULT_BLOB)
}

private val EN_SESSION_INFO_RESULT_BLOB = """
    5aa5695b 96ca5395 96ca5395 a569a569 96ca5395 96ca4fba 
    a56df0ae 35f35aa5 6996ca53 955aa569 5a5a5a5a 5a96ca53 
    955a5a96 ca53955a 5a5a5a5a a5695a   
""".toBA()
