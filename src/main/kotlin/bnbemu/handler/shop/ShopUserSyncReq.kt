package bnbemu.handler.shop

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.server.User

/**
 * C->S op 37: 进入商城前人物槽位与装备数据同步 (ShopUserSyncReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 客户端收到 op 259 成功应答后，于 0x005BE647 构造发送:
 *   - Encode1(action): 场景标识 (2)
 *   - Encode1(count): 角色名数量
 *   - repeat(count) { EncodeStr(name); Encode4(id) }
 *   - Encode1(param): 0
 *   - Encode4(val): 附加数据
 * - 实测（EN，帐号 fffff）`58 5B A5 6D F0 AE 35 F3 96 CA 4F BA 5A 96 CA 53 95`:
 *   action=2, count=1, name 4 字, id=7215 (0x1C2F), param=0, extra=0。
 *
 * 商城是**独立连线**，该连线上没有 `User` 实例（`ClientSocket.users` 为空），
 * 于是购买 (op 267) / 273 上报时拿不到玩家 → 背包记不下来。
 * 这里用 op 37 里带的 id/name 给商城连线补一个 `User`；
 * 背包与装备都存放在进程级仓库（[bnbemu.server.PlayerBag] / [bnbemu.server.PlayerEquips]），
 * 因此和频道连线共享同一份数据。
 */
val ShopUserSyncReq = PKTHandler { c ->
    val action = Decode1
    val count = Decode1
    repeat(count) {
        val name = DecodeStr
        val playerId = Decode4
        if (c.users.isEmpty() && playerId != 0) {
            c.users.add(User(c, playerId, name.ifBlank { "Player" }))
            println("[ShopUserSyncReq] 商城连线绑定玩家 id=$playerId name='$name'")
        }
    }
    val param = Decode1
    val extra = Decode4
}
