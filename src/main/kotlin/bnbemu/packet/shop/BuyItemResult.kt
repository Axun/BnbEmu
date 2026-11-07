package bnbemu.packet.shop

import bnbemu.netty.PKT

/**
 * S→C op 202: 商城购买结果 (BuyItemResult)
 *
 * 逆向确认 (CA.exe EN v18, 2026-11-07 实测修正)：
 * 商城舞台 `CShopStageNew::OnPacket` (`sub_5D5CBA` @0x005D5CBA) 直接按 **opcode 202** 分派：
 * ```
 * v7 = opcode - 202;
 * if (v7 == 0) {                        // 购买结果
 *   if (dword_99B80C == 0
 *    && (feature5 关 || dword_99BCDC == 0 || [basket+0x1D3C] == 0)
 *    && (feature30 关 || dword_99BCE8 == 0)) {
 *       sub_5D171B(this, pkt);          // 应用购买结果
 *       sub_5CDC64(this);
 *       sub_671C3A(CUserInventoryMan);
 *       CSendPacket(273, E1(2), E4(playerIdx));   // 回执，刷新装备
 *       return;
 *   }
 * }
 * ```
 * 之前试过的 op 267 / op 268 都到不了这里：
 *  - 267 在总分派 `sub_502AE1` 里有专属 case，处理完 `return`，不广播；
 *  - 268 只会落到 `default: goto LABEL_16` 的 Stage 广播段，而那段广播目标是
 *    购物篮 / 优惠券商店单例，商城舞台根本不经过那条路。
 *
 * `sub_5D171B` (`0x005D171B`) 的读包顺序（offset 从 opcode 之后开始）：
 *   1. `E1(type)`        ← 22 = 礼包/赠送分支（作为 sub_66B4ED 的 a7），其它走普通购买；
 *   2. `sub_66B4ED(...)` 从同一偏移继续读 **8 字节到期时间**（`sub_78F1C0(buf, 8)`，按 dword 反混淆）；
 *   3. 按客户端自己选中的商品（stage 里挂在 `+0x7B00` 一带）调 `sub_66A017` 把物品应用进背包；
 *   4. 收尾 `sub_5CDC64(...)` 刷新商城槽位。
 *
 * 因此包体 = `E1(type) + E4(expireHi) + E4(expireLo)`（**不含** 202，202 本身就是 opcode），
 * 到期时间传 0 表示永久。
 */
const val BUY_RESULT_OK = 202

/** type: 0=普通购买；22=礼包/赠送分支（`v55 == 22` 会作为 sub_66B4ED 的第 7 个参数 a7） */
fun BuyItemResult(type: Int = 0, expire: Long = 0L) = PKT {
    Encode1(type)
    // 8 字节到期时间按两个 Encode4 写（客户端 sub_78F1C0(buf, 8) 按 dword 反混淆）
    Encode4((expire ushr 32).toInt())
    Encode4((expire and 0xFFFFFFFFL).toInt())
}
