package bnbemu.handler.shop

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.ShopCatalog
import bnbemu.packet.UserDynamicInfo
import bnbemu.packet.shop.BuyItemResult
import bnbemu.server.UserItemEntry
import bnbemu.server.lucci

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
 * 模拟器现状: 只做「查到商品 → 回 op 202 购买结果 → 同步金币」的最小闭环；
 * 物品由客户端自己在 `sub_5D171B` → `sub_66B4ED` 里应用进背包。
 * 扣款尚未接入。
 */
val BuyItemReq = PKTHandler { c ->
    if (readableBytes() < 4) return@PKTHandler

    val prodId = Decode4
    val arg = if (readableBytes() >= 4) Decode4 else 0
    val flag = if (readableBytes() >= 1) Decode1 else 0

    val entry = ShopCatalog.DEFAULT_ITEMS.firstOrNull { it.prodId == prodId }
    println(
        "[BuyItemReq] prodId=$prodId arg=$arg flag=$flag -> " +
            (entry?.let { "itemId=${it.itemId} cat=${it.category} name='${it.name}' count=${it.count}" }
                ?: "目录里没找到该 prodId")
    )

    // 购买结果：type=0（普通购买），到期时间 0 = 永久
    c send BuyItemResult(type = 0)

    // 服务端侧记录购买结果，保证重进频道/重连后物品还在。
    // 物品本体由客户端在 sub_5D171B -> sub_66B4ED 里自己塞进背包向量，
    // 这里只维护「服务端认账的背包清单」，下次 JoinSessionReq 用 op 320 补发。
    val user = c.users.firstOrNull()
    if (user != null && entry != null && user.items.none { it.itemId == entry.itemId }) {
        user.items += UserItemEntry(itemId = entry.itemId, count = 1)
        println("[BuyItemReq] 已记入服务端背包: itemId=${entry.itemId}, 当前背包=${user.items.map { it.itemId }}")
    }

    // 商城界面余额走 CUserDynamicInfo (op 122)，购买后补发一次避免显示漂移
    c send UserDynamicInfo(0, c.lucci)
}
