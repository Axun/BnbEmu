package bnbemu.handler.shop

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler

/**
 * C→S op 316 (0x13C): 商城连线状态上报 (ShopConnStateReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点 `sub_5BE296` (`0x005BE296`): `CSendPacket_CtorWithOp(316)` + `E1(state)`。
 * - 调用点 `sub_5DF695` (`0x005DF695`) 在 `a2 == 8`（关闭商城/回大厅）时调用：
 *   把 `CShopConnectionMan + 4` 的当前状态（实测 0 = 空闲/已退出）上报给服务端。
 * - 这是**纯通知**，真实服务器不回包（客户端也不等待）。
 *
 * 之前未注册，退出商城回大厅时会刷两条 `[UNKNOWN] 316 找不到 Handler`。
 */
val ShopConnStateReq = PKTHandler { c ->
    val state = if (readableBytes() >= 1) Decode1 else -1
    println("[ShopConnStateReq] op316 商城连线状态上报 state=$state")
}
