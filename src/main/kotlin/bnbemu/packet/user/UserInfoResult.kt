package bnbemu.packet.user

import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.codec.encodeName

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

data class UserEquipEntry(
    val itemId: Int,
    val slot: Int,
    val param1: Int = 1,
    val param2: Int = 0
)

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
 *       +0x94: 经验千分比 0..999 — CMyPageDialog::InitData 把它存进对话框 +108212，
 *              绘制点 0x005DD121 用 `60 * v / 999` 分四段画 "Etc/ExpImg" 经验条。
 *              ⚠️ 只发 0x8c/0x90 不够，经验条读的是这个字段（0 就是一直空/显示 0）。
 *       +0xa0/+0xa1/+0xa2: 最近战绩 胜/负/平 (E1)。feature 47 开启时 0x005E8766 用它算
 *              "RecentWinRate" = (win/(win+lose) + draw*0.001) * 100，个人主页的
 *              "Win Rate" 行取的就是这个（只发 +0x78/+0x80 的总体战绩时，Record 正确但胜率恒 0.0%）。
 *       +0xac/+0xb0/+0xb4: 排位战绩 胜/平/负 (E4)，0x005E8756 的另一路胜率用 win/(win+lose)。
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
    characterName: String = "",
    gender: Int = 0, // 0=男, 1=女
    motto: String = "PopTag Forever!", // 个性签名 (Profile)
    guildName: String = "",
    guildRank: Int = 0,
    win: Int = 0,
    draw: Int = 0,
    lose: Int = 0,
    curExp: Int = 0,
    maxExp: Int = 1000,
    expPercent: Int = 0,
    /** [esi+0xa0] 最近战绩-胜 (E1) */
    recentWin: Int = 0,
    /** [esi+0xa1] 最近战绩-负 (E1) */
    recentLose: Int = 0,
    /** [esi+0xa2] 最近战绩-平 (E1) */
    recentDraw: Int = 0,
    /** [esi+0xac] 排位战绩-胜 (E4) */
    rankedWin: Int = 0,
    /** [esi+0xb0] 排位战绩-平 (E4) */
    rankedDraw: Int = 0,
    /** [esi+0xb4] 排位战绩-负 (E4) */
    rankedLose: Int = 0,
    level: Int = 5,
    favorite: String = "Bazzi",
    sweetieName: String = "",
    equips: List<UserEquipEntry> = emptyList()
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
        Encode4(expPercent) // [esi + 0x94] 经验千分比 0..999（个人主页经验条只看这个字段）
        Encode1(0) // [esi + 0x125]
        Encode4(0) // [esi + 0x128]
        EncodeTokenStr("") // [esi + 0x12c]
        Encode2(0) // [esi + 0x144]
        EncodeTokenStr("") // [esi + 0x148]
        Encode4(0) // [esi + 0x160]
        // [esi + 0xa0..0xa2] 最近战绩 (win/lose/draw)：feature 47 开启时客户端用它算
        // "RecentWinRate" (0x005E8766: `(win/(win+lose) + draw*0.001) * 100`)，个人主页胜率行显示的就是它
        Encode1(recentWin.coerceIn(0, 255))
        Encode1(recentLose.coerceIn(0, 255))
        Encode1(recentDraw.coerceIn(0, 255))
        Encode4(0) // [esi + 0xa8]
        Encode1(0) // [esi + 0xa4]
        // [esi + 0xac..0xb4] 排位战绩 (win/draw/lose)：0x005E8756 的排位胜率用 win/(win+lose)
        Encode4(rankedWin)
        Encode4(rankedDraw)
        Encode4(rankedLose)
        Encode4(0) // [esi + 0xbc]
        Encode4(0) // [esi + 0xc4]
        Encode1(0) // [esi + 0xc0]
        Encode4(0) // [esi + 0xfc]
        Encode4(0) // [esi + 0x100]
        Encode4(equips.size) // [esi + 0xec] subListCount (装备槽位列表)
        equips.forEach { eq ->
            Encode4(eq.itemId)
            Encode1(eq.slot)
            Encode4(eq.param1)
            Encode4(eq.param2)
        }
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
