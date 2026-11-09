package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 254: 頻道用戶佔位分佈 / 在線用戶地圖 (進頻道批次回應之一, seq=70, 54B)
// ============================================================================
// 逆向確認:
//   CA.exe 0x005030E1 (CStage::OnPacket) -> 0x004F83B9 -> 0x004E09D6 (CUserMap::OnPacket).
//   處理類: TSingleton<CUserMap> (0x00998414, 虛表 0x0089DE24).
//   結構:
//     Encode1(hasUser): 是否有用戶
//     若有用戶 (0x004E061C):
//       Encode2(len) + string: 會話字串 / 用戶標識 (f0ae35f3)
//       Encode1(v1) + Encode1(v2) + Encode4(v3) + Encode1(v4) + Encode4(v5)
//       Encode2(level) + Encode4(v7=50) + Encode4(v8) + Encode4(v9) + Encode4(v10)
//       Encode1(v11) + Encode4(v12) + Encode2(s1) + Encode2(s2) + Encode2(s3) + Encode2(s4)
//     隨後 0x004E0A3B:
//       Encode4(tail4) + Encode1(tail1)
//     隨後 0x004F83ED 將各槽狀態同步至 CUserDynamicInfo (0x00993E70) 的 [edi + esi + 0x2B8].
//  大厅左上角显示的等级就是这里提供的（下方在线列表里不是）
data class UserMapEntry(
    val session: ByteArray = SESSION_STRING,
    val v1: Int = 0,
    val v2: Int = 0,
    val v3: Int = 0,
    val v4: Int = 0,
    val v5: Int = 0,
    val level: Int = 5,
    val v7: Int = 50,
    val v8: Int = 0,
    val v9: Int = 0,
    val v10: Int = 0,
    val v11: Int = 0,
    val v12: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val tail4: Int = 0,
    val tail1: Int = 0
)

fun UserMapInfo(
    user: UserMapEntry? = UserMapEntry()
) = PKT {
    if (user == null) {
        Encode1(0)
    } else {
        Encode1(1)
        Encode2(user.session.size)
        EncodeBuffer(user.session)
        Encode1(user.v1)
        Encode1(user.v2)
        Encode4(user.v3)
        Encode1(user.v4)
        Encode4(user.v5)
        Encode2(user.level)
        Encode4(user.v7)
        Encode4(user.v8)
        Encode4(user.v9)
        Encode4(user.v10)
        Encode1(user.v11)
        Encode4(user.v12)
        Encode2(user.s1)
        Encode2(user.s2)
        Encode2(user.s3)
        Encode2(user.s4)
        Encode4(user.tail4)
        Encode1(user.tail1)
    }
}
