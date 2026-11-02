package bnbemu.packet

import bnbemu.netty.PKT

// 頻道連線內的 65B op 4 (enter-channel.pcap 線序 seq=85, 批次回應之一)
// 與登入連線的 AskFirstInfoResult 同 opcode 同欄位集 (UserInfo, 欄位表見該文件):
// 僅保留 userId2 與 4B 會話串, 其餘數值欄全 0
fun SessionInfoResult() = PKT {
    UserInfo(userId2 = CAPTURE_USER_ID, session = SESSION_STRING).Encode(this)
}
