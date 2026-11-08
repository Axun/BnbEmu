package bnbemu.packet

import bnbemu.netty.PKT

// 頻道連線內的 65B op 4 (enter-channel.pcap 線序 seq=85, 批次回應之一)
// 與登入連線的 AskFirstInfoResult 同 opcode 同欄位集 (UserInfo, 欄位表見該文件):
// 僅保留 userId2 與 4B 會話串, 其餘數值欄全 0。
// ⚠️ channelTabCount 例外: 抓包為 0, 但客戶端會用本包覆寫 [CUserInfo+0x20C],
// 令 sub_626742 回傳 0 而否決全部頁索引 (記住的 LastChannelType 被丟棄、每次退回 Beginner 頁)。
// 故與登入版統一為 CHANNEL_TAB_COUNT; replay_test 的 65B 基準只豁免末位元組。
fun SessionInfoResult() = PKT {
    UserInfo(
        userId2 = CAPTURE_USER_ID, session = SESSION_STRING,
        channelTabCount = CHANNEL_TAB_COUNT,
    ).Encode(this)
}
