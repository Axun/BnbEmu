package bnbemu.handler.shop

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler

/**
 * C→S op 294 (0x126): 商城购买后的背包/槽位同步上报 (ShopInvenSyncReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 客户端收到 S→C op 202 (购买结果) 后, `CShopStageNew::OnPacket` (`sub_5D5CBA`)
 *   的成功分支依次做三件事:
 *   1. `sub_5D171B`  应用购买结果（按选中商品把物品塞进背包向量）
 *   2. `sub_5CDC64`  构造并发送 **op 294**
 *   3. `sub_671C3A`  + 发送 op 273 (action=2) 上报装备/背包变化
 * - `sub_5CDC64` (`0x005CDC64`) 包体:
 *   - `E1(CShopStageNew + 0x7B38)` = 本地玩家索引
 *   - `sub_68D9FA(pkt)` = 先写包尾校验 (vtable+100), 再让 `dword_993D58`
 *     （商城道具管理器）追加自己的状态；实测该管理器没有额外内容，
 *     所以线上只有 1 字节（日志：`5A` → E1(0)）。
 *
 * 因此这是**纯通知**：客户端告诉服务端「我刚买完，商城槽位/背包状态已变」，
 * 不需要任何回包（真实服务器也没有对应 S→C 响应，客户端不会等待）。
 * 模拟器的处理：读掉玩家索引，记录一行日志即可；如果客户端以后带上更多负载，
 * 按剩余字节吃掉，避免影响后续包解析。
 */
val ShopInvenSyncReq = PKTHandler { c ->
    val playerIndex = if (readableBytes() >= 1) Decode1 else -1
    println("[ShopInvenSyncReq] op294 商城背包同步上报 player=$playerIndex 附加负载=${readableBytes()}B")
}
