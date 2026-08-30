package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// AskFirstInfo (C→S op 2, 登錄連線第一個業務包, op 之後跟 40B 票據資料) 的回應
// login.pcap sq=43, 65B; 另有 13B 變體 (enter-channel.pcap 內應答頻道內查詢) 尚未實作
// 明文字段: E1(0) E2(1) E1(1) E4(1000) E4(7215) E2(0) E2(0) E4(0) E4(7215)
//   E2(4)+"f0ae35f3"(4B 會話串) E1(0) E2(0) E4(0) E1(0) E2(1) E1(0)×5 E4(0) E1(0) E1(0) E4(0) E1(0) E1(1) E1(0) E1(0) E1(0) E4(0x?)...
// 原樣重放 (內含抓包帳號的會話串/數值, 模擬器暫無帳號體系)
fun AskFirstInfoResult() = PKT {
    EncodeBuffer(EN_ASK_FIRST_INFO_RESULT_BLOB)
}

private val EN_ASK_FIRST_INFO_RESULT_BLOB = """
    5aa5685b 96ca507d 96ca4fba a569a569
    96ca5395 96ca4fba a56df0ae 35f35aa5
    6996ca53 955aa568 5a5a5a5a 5a96ca53
    955a5a96 ca53955a 5b5a5a5a b6e159
""".toBA()
