package bnbemu.handler.shop

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler

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
 */
val ShopUserSyncReq = PKTHandler { c ->
    val action = Decode1
    val count = Decode1
    repeat(count) {
        DecodeStr
        Decode4
    }
    val param = Decode1
    val extra = Decode4
}
