package pingu.packet

import pingu.netty.PKT
import pingu.netty.SendPacketBase

/**
 * 字符串 Token 编码 (与 SessionList.kt 令牌算法同款):
 * CA.exe 0x0078F910 在 degree 1..3 帧中调用 0x0075DAF0 对字符串反混淆，
 * 故服务端发送非空字符串必须使用 token 编码，否则客户端解码后显示乱码。
 */
private fun SendPacketBase.EncodeTokenStr(s: String) {
    if (s.isEmpty()) {
        Encode2(0)
    } else {
        val token = encodeName(s)
        Encode2(token.size)
        EncodeBuffer(token)
    }
}

/**
 * S->C op 73: 玩家详细资料回应 (PUserInfoResult)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 全局注册: CA.exe 0x004FFFCF 注册 CPacketCreatorImpl<PUserInfoResult, enGameServerPacket, 73>
 * - 反序列化: CA.exe 0x00449F23 (PUserInfoResult::Decode), 共 59 个字段
 * - 派发处理: CA.exe 0x004FBB6F (CStage::OnUserInfoResult)
 *   当 errCode == 0:
 *   - isSelf == 1: 构造 CMyPageDialog (0x005E5688) 存入单例 0x0099B80C,
 *     调用 CMyPageDialog::InitData (0x005E2790) 初始化 UI 并展示
 *     - 控件对应:
 *       +0x44: Nickname (昵称)
 *       +0x74: Gender (性别: 0=男, 1=女)
 *       +0x180: Profile (个性签名, Tab 1)
 *       +0x168: Guild (公会名)
 *       +0x78: Wins (胜场)
 *       +0x7c: Draws (平局)
 *       +0x80: Losses (负场)
 *       +0x8c: curExp (当前经验)
 *       +0x90: maxExp (升级经验)
 *       +0x2c: Sweetie (伴侣名, 空则无伴侣)
 *       +0x5c: Favorite (最喜爱角色/模式)
 *       +0x98: Level (等级数值)
 *       (Lucci 金币由 CUserDynamicInfo::Instance().Get(0) 获取, 由 UserDynamicInfo op 122 同步)
 *   - isSelf == 0: 构造 COtherUserPageDialog (0x005E58E4) 存入单例 0x0099B810,
 *     调用 COtherUserPageDialog::InitData (0x005E2F9B) 展示他人名片
 */
fun UserInfoResult(
    errCode: Int = 0,
    isSelf: Boolean = true,
    slotIndex: Int = 0,
    characterName: String = "ffdf",
    gender: Int = 0, // 0=男, 1=女
    motto: String = "PopTag Forever!", // 个性签名 (Profile)
    guildName: String = "",
    guildRank: Int = 0,
    win: Int = 0,
    draw: Int = 0,
    lose: Int = 0,
    curExp: Int = 0,
    maxExp: Int = 1000,
    level: Int = 5,
    favorite: String = "Bazzi",
    sweetieName: String = ""
) = PKT {
    Encode4(errCode)
    if (errCode == 0) {
        Encode4(if (isSelf) 1 else 0)
        if (isSelf) {
            Encode1(slotIndex)
        }
        EncodeTokenStr(characterName) // [esi + 0x44] Nickname
        Encode1(gender) // [esi + 0x74] Gender
        EncodeTokenStr(motto) // [esi + 0x180] Profile 签名
        EncodeTokenStr(guildName) // [esi + 0x168] 公会名
        Encode1(guildRank) // [esi + 0x164] 公会级别
        // feature 4 未启用，跳过 str104
        Encode4(win) // [esi + 0x78] Wins
        Encode4(draw) // [esi + 0x7c] Draws
        Encode4(lose) // [esi + 0x80] Losses
        Encode4(curExp) // [esi + 0x8c] curExp
        Encode4(maxExp) // [esi + 0x90] maxExp
        Encode4(0) // [esi + 0x94]
        Encode1(0) // [esi + 0x125]
        Encode4(0) // [esi + 0x128]
        EncodeTokenStr("") // [esi + 0x12c]
        Encode2(0) // [esi + 0x144]
        EncodeTokenStr("") // [esi + 0x148]
        Encode4(0) // [esi + 0x160]
        Encode1(0) // [esi + 0xa0] Monster win
        Encode1(0) // [esi + 0xa1] Monster lose
        Encode1(0) // [esi + 0xa2] Monster rank
        Encode4(0) // [esi + 0xa8]
        Encode1(0) // [esi + 0xa4]
        Encode4(0) // [esi + 0xac]
        Encode4(0) // [esi + 0xb0]
        Encode4(0) // [esi + 0xb4]
        Encode4(0) // [esi + 0xbc]
        Encode4(0) // [esi + 0xc4]
        Encode1(0) // [esi + 0xc0]
        Encode4(0) // [esi + 0xfc]
        Encode4(0) // [esi + 0x100]
        Encode4(0) // [esi + 0xec] subListCount
        // feature 0x16 未启用，跳过 0xcc..0xe4
        Encode4(0) // [esi + 0x84]
        Encode4(0) // [esi + 0x88]
        EncodeTokenStr("") // [esi + 0x198]
        Encode2(0) // [esi + 0x1b0]
        EncodeTokenStr(sweetieName) // [esi + 0x2c] Sweetie / 伴侣名
        EncodeTokenStr(favorite) // [esi + 0x5c] Favorite
        Encode4(level) // [esi + 0x98] Level 数值
        Encode4(0) // [esi + 0x9c]
        Encode4(0) // [esi + 0x11c]
        Encode4(0) // [esi + 0x120]
        Encode1(0) // [esi + 0x124]
        Encode4(0) // [esi + 0x28]
        Encode4(0) // [esi + 0x1b4]
        Encode1(0) // [esi + 0x1b8]
        EncodeTokenStr("") // [esi + 0x1bc]
        Encode4(0) // [esi + 0x1d4]
        EncodeTokenStr("") // [esi + 0x1d8]
        EncodeTokenStr("") // [esi + 0x1f0]
        Encode1(0) // [esi + 0x20c]
        Encode4(0) // [esi + 0x210]
        // feature 6 未启用，跳过 0x214, 0x218
        Encode1(0) // [esi + 0x21c]
        Encode1(0) // [esi + 0x21d]
        Encode4(0) // [esi + 0x248] medalsCount
        EncodeTokenStr("") // [esi + 0x230]
        repeat(4) { Encode4(0) } // [esi + 0x21e] 16 字节安全缓冲区
    }
}
