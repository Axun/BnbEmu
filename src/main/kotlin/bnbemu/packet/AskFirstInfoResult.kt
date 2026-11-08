package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.netty.toBA

// AskFirstInfo (C→S op 2, 登錄連線第一個業務包, op 之後跟 40B 票據資料) 的回應
// login.pcap sq=43, 65B (63B body); 內含抓包帳號 kcworms1 的身份值 — 模擬器暫無帳號體系,
// 暫以抓包值重放 (會話串另見 op7 OnConnEstablished / op254 / op297, 同串)
fun AskFirstInfoResult() = PKT {
    UserInfo(
        userId = CAPTURE_USER_ID, userId2 = CAPTURE_USER_ID, session = SESSION_STRING,
        v2 = 1, v4 = 1000, v16 = 1, v27 = 1, v31 = 5000,
        channelTabCount = CHANNEL_TAB_COUNT,
    ).Encode(this)
}

// 抓包帳號 kcworms1 的身份值 (userId 7215 = passport 0x2E 回應的賬號數值; 暫無帳號體系故用常量)
internal const val CAPTURE_USER_ID = 7215
internal val SESSION_STRING = "f0ae35f3".toBA()

// 頻道選擇頁的頁籤數上限 (op 4 末位元組 E1)。
// 逆向: CA.exe 0x006220A8 解析 op 4 時把 E2(v31) 寫入 [CUserInfo+0x204]、E1 寫入 +0x20C
//   (0x006226D1 / 0x006226E9); sub_626742 (0x00626742) 以 +0x20C 為頁索引合法範圍上限,
//   sub_6296B6 / sub_628EC6 據此校驗 LastChannelType 與切頁請求。
//   值為 0 時分母為 0, 所有頁索引都被判越界 → 客戶端丟棄記住的頁籤、退回 Beginner 頁。
// 抓包登入版 = 3; 頻道連線版 (SessionInfoResult) 原為 0 並會覆寫同一欄位。
// 模擬器兩版統一為 3, 避免進過普通頻道後頁籤保存失效 (replay_test 65B 基準只豁免末位元組)。
internal const val CHANNEL_TAB_COUNT = 3

// op 4 的 65B 版欄位集 (login.pcap sq=43 / enter-channel.pcap seq=85, 63B body 逐字節解出):
//   E1(0) E2(v2) E1(1) E4(v4) E4(userId) E2(0) E2(0) E4(0) E4(userId2) E2(len)+session
//   E1(0) E2(0) E4(0) E1(0) E2(v16) E1(0)×5 E4(0) E1(0) E1(0) E4(0) E1(0) E1(v27) E1(0)×3
//   E2(v31) E1(channelTabCount)
// 語義已釘死: userId/userId2 = 賬號數值 (passport 0x2E 同值; 登入版兩處皆 7215, 頻道版僅
//   userId2 保留 7215、userId 為 0), session = 4B 會話串 (兩變體同串)。
//   channelTabCount = 頻道頁籤數上限, 詳見 CHANNEL_TAB_COUNT 註釋 (兩版統一為 3; 抓包頻道版為 0)。
// 其餘 v* 語義未知, 僅登入版有值 (v2=1, v4=1000, v16=1, v27=1, v31=5000;
// 5000 亦見 op122 UserDynamicInfo 金幣同步與 passport 0x2E)。13B 變體 (UserSimpleInfoResult) 結構不同, 不走本類
class UserInfo(
    val userId: Int = 0,
    val userId2: Int = 0,
    val session: ByteArray = ByteArray(0),
    val v2: Int = 0,
    val v4: Int = 0,
    val v16: Int = 0,
    val v27: Int = 0,
    val v31: Int = 0,
    val channelTabCount: Int = CHANNEL_TAB_COUNT,
) {
    fun Encode(p: SendPacketBase) {
        p.Encode1(0)
        p.Encode2(v2)
        p.Encode1(1)
        p.Encode4(v4)
        p.Encode4(userId)
        p.Encode2(0)
        p.Encode2(0)
        p.Encode4(0)
        p.Encode4(userId2)
        p.Encode2(session.size)
        p.EncodeBuffer(session)
        p.Encode1(0)
        p.Encode2(0)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode2(v16)
        repeat(5) { p.Encode1(0) }
        p.Encode4(0)
        p.Encode1(0)
        p.Encode1(0)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode1(v27)
        repeat(3) { p.Encode1(0) }
        p.Encode2(v31)
        p.Encode1(channelTabCount)
    }
}
