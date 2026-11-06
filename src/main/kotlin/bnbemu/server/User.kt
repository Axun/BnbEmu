package bnbemu.server

import bnbemu.netty.ClientSocket

/** 模拟器暂无账号体系时的默认金币 (Lucci); 所有金币同步统一取此值, 避免各处硬编码漂移。 */
const val DEFAULT_LUCCI = 1234567

/**
 * 当前连线的金币余额。
 * 进频道/进商城可能各自新开连线, 此时 `c.users` 为空, 回落 [DEFAULT_LUCCI]。
 * 客户端金币显示统一读 CUserDynamicInfo 单例 (CA.exe 0x993E70 / Get(0) 0x6E4E0D),
 * 由 S->C op 122 UserDynamicInfo 写入 (0x4F80D0 -> Set 0x6E4E23)。
 */
val ClientSocket.lucci: Int
    get() = users.firstOrNull()?.lucci ?: DEFAULT_LUCCI

class User(
    val c: ClientSocket,
    val id: Int,
    val name: String,
    val level: Int = 5,
    val lucci: Int = DEFAULT_LUCCI,
    val exp: Int = 0,
    val win: Int = 0,
    val draw: Int = 0,
    val lose: Int = 0,
    val motto: String = "PopTag Forever!",
    val favorite: String = "Bazzi"
)