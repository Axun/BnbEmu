package bnbemu.handler.shop

import bnbemu.data.shop.ShopCatalogData
import bnbemu.data.shop.ShopCurrency
import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.shop.BUY_RESULT_ALREADY_HAVE
import bnbemu.packet.shop.BUY_RESULT_ERROR
import bnbemu.packet.shop.BUY_RESULT_SUCCESS
import bnbemu.packet.shop.BuyItemResult
import bnbemu.packet.shop.CashBalance
import bnbemu.packet.user.UserDynamicInfoValue
import bnbemu.service.PlayerService
import bnbemu.service.PurchaseStatus
import bnbemu.service.lucci
import bnbemu.service.player

/**
 * C→S op 267: 商城购买请求 (BuyItemReq)
 *
 * 逆向确认 (CA.exe EN v18, 2026-11-07):
 * - 发包点: `CShopStageNew::OnBuy` (`0x006A846A`, CA.exe.c:625531)
 *   → `sub_669D26` (`0x00669D26`) → `CSendPacket_BeginOp(..., 267)` + `sub_665F54` (`0x00665F54`)：
 *     - `E4(prodId)`  ← `sub_6610C4(商品)` = 商品对象 `+8`，即 op 65 目录里的 **prodId**
 *     - `E4(arg)`     ← `a4`（购买数量/参数，客户端默认 1）
 *     - `E1(flag)`    ← 仅当客户端特性 16 开启时追加（`sub_6C05A2(16)`），实测为 0
 *   发完把等待锁 `[shop+0xC0] = 1`，**必须**收到 S→C **op 202** 才解除
 *   （否则客户端一直卡住、再点购买只弹 "The server is processing another request…"）。
 * - 实测请求体（EN 客户端点 "Bomb 655"）：`A1 A4 FC 9A | 96 CA 53 95 | 5A`
 *   = `E4(930000655 = Bomb 655 的 prodId) + E4(0) + E1(0)`。
 * - 结果包 opcode = **202**：商城舞台自己的收包函数 `sub_5D5CBA` 里有
 *   `v7 = opcode - 202; if (v7 == 0) { sub_5D171B(...); }`，见
 *   `packet/shop/BuyItemResult.kt` 的完整注释。
 *
 * 完整扣款与入包流程: 查商品 → 校验余额与持有/堆叠上限 → 扣除 Lucci / NX Cash →
 * 写入/累加玩家背包 → 回 op 202 购买结果 → 同步 Lucci (op 122) 与 Cash (op 201)。
 */
val BuyItemReq = PKTHandler { c ->
    if (readableBytes() < 4) return@PKTHandler

    val prodId = Decode4
    val arg = if (readableBytes() >= 4) Decode4 else 0
    val flag = if (readableBytes() >= 1) Decode1 else 0

    val entry = ShopCatalogData.findByProdId(prodId)
    println(
        "[BuyItemReq] prodId=$prodId arg=$arg flag=$flag -> " +
            (entry?.let {
                "itemId=${it.itemId} cat=${it.category} name='${it.name}' count=${it.count} " +
                    "price=${it.price} currency=${it.currency}"
            } ?: "目录里没找到该 prodId")
    )

    val player = c.player
    if (player == null || entry == null) {
        c send BuyItemResult(type = BUY_RESULT_ERROR)
        c send UserDynamicInfoValue(0, c.lucci)
        return@PKTHandler
    }

    val result = PlayerService.purchaseCatalogItem(player.userId, entry)
    if (!result.success) {
        val errCode = when (result.status) {
            PurchaseStatus.ALREADY_OWNED -> BUY_RESULT_ALREADY_HAVE
            else -> BUY_RESULT_ERROR
        }
        println(
            "[BuyItemReq] 购买失败: status=${result.status} errCode=$errCode " +
                "lucci=${result.lucci} cash=${result.cash}"
        )
        c send BuyItemResult(type = errCode)
        c send UserDynamicInfoValue(0, result.lucci)
        if (entry.currency == ShopCurrency.CASH) {
            c send CashBalance(result.cash)
        }
        return@PKTHandler
    }

    // 购买结果：type=0（普通购买），到期时间 0 = 永久
    c send BuyItemResult(type = BUY_RESULT_SUCCESS, expire = 0L)
    println(
        "[BuyItemReq] 购买成功: itemId=${entry.itemId} newCount=${result.itemCount} " +
            "lucci=${result.lucci} cash=${result.cash}, 当前背包=${player.items.map { "${it.itemId}x${it.count}" }}"
    )

    // 商城界面余额同步：Lucci 走 CUserDynamicInfo (op 122)，NX Cash 走 CashBalance (op 201)
    c send UserDynamicInfoValue(0, result.lucci)
    if (entry.currency == ShopCurrency.CASH) {
        c send CashBalance(result.cash)
    }
}
