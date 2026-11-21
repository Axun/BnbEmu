package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.shop.CashBalance
import bnbemu.packet.user.UserDynamicInfoValue
import bnbemu.service.PlayerService

/**
 * C->S op 277 (0x115): 商城 "Charge NX" 充值点券请求
 *
 * 逆向确认 (CA.exe EN v18):
 * - 客户端 sub_663486 (0x00663486, CA.exe.c:563492) 用 `CSendPacket_CtorWithOp(v1, 277)`
 *   构造并立即发送, **空包无 payload**; 调用方 sub_5C0A7D (0x005C0A7D, CA.exe.c:428555)
 *   在商城点充值时, 若 `sub_6C04F0(65)` 未开或本地 NXClub 充值 URL 为空, 就走这条分支
 *   (否则走 sub_4FE671 直接 ShellExecuteA 打开充值网页并另发 C->S 283)。
 * - 客户端发完 277 后 `return 1`, 无等待锁、无 S->C 回应等待, 界面本身不会给任何提示。
 *
 * 模拟器行为: 原版没有独立的金币充值按钮，这里复用商城 Charge NX 按钮：
 * 每次请求同时给玩家加 [CHARGE_NX_AMOUNT] 点券与 [CHARGE_LUCCI_AMOUNT] 金币，并下发
 * - S->C op 201 `CashBalance(余额)`（商城 stage case 201 -> sub_5CFE29 -> sub_6E4AFE）
 * - S->C op 122 `UserDynamicInfoValue(0, 余额)`（透传 sub_502AE1 -> sub_4F80D0 刷新 Lucci）
 */
const val CHARGE_NX_AMOUNT = 10_000
const val CHARGE_LUCCI_AMOUNT = 1_000_000

val ChargeNxReq = PKTHandler { c ->
    val userId = c.playerId ?: PlayerService.activeProfile.userId
    c send CashBalance(PlayerService.chargeCash(userId, CHARGE_NX_AMOUNT))
    c send UserDynamicInfoValue(0, PlayerService.chargeLucci(userId, CHARGE_LUCCI_AMOUNT))
}
