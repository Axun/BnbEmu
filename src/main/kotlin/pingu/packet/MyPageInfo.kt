package pingu.packet

import pingu.netty.PKT

// ============================================================================
// S→C op 227: 個人資料摘要 / 戰績與商城版本 (進頻道批次回應之一, seq=88, 35B)
// ============================================================================
// 逆向確認:
//   CA.exe 0x005E4AF8 (CMyPageDialog 分派) -> 0x005E2CEE -> 0x005E0259.
//   處理類: TSingleton<CMyPageDialog> (0x0099B80C, 虛表 0x008AF9AC).
//   欄位結構:
//     Encode1(hasProfile): 1
//     Encode4: 商城目錄版本 hash (0x75D92163, 與 op 261 同值, 傳入 CShopItemMan::CheckRevision)
//     Encode4: 0 (套餐/活動版本)
//     Encode4: 0 (折扣/橫幅版本)
//     Encode2(4) + string: 4B 會話字串 (f0ae35f3)
//     Encode1: feature0x1a (0)
//     Encode4: userCount (1)
//     userCount × [
//       Encode1: slotIndex (0)
//       Encode4: 玩家 userId (7215)
//       Encode2(4) + string: 4B 會話字串 (f0ae35f3)
//     ]
data class MyPageUser(
    val slotIndex: Int = 0,
    val userId: Int = CAPTURE_USER_ID,
    val session: ByteArray = SESSION_STRING
)

fun MyPageInfo(
    hasProfile: Boolean = true,
    shopVersion: Int = 0x75D92163,
    packageVersion: Int = 0,
    bannerVersion: Int = 0,
    session: ByteArray = SESSION_STRING,
    feature0x1a: Int = 0,
    users: List<MyPageUser> = listOf(MyPageUser())
) = PKT {
    Encode1(if (hasProfile) 1 else 0)
    if (hasProfile) {
        Encode4(shopVersion)
        Encode4(packageVersion)
        Encode4(bannerVersion)
        Encode2(session.size)
        EncodeBuffer(session)
        Encode1(feature0x1a)
        Encode4(users.size)
        for (u in users) {
            Encode1(u.slotIndex)
            Encode4(u.userId)
            Encode2(u.session.size)
            EncodeBuffer(u.session)
        }
    }
}
