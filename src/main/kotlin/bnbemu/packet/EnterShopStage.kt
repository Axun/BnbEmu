package bnbemu.packet

import bnbemu.netty.PKT

/**
 * S->C op 17: 进入商城阶段 (EnterShopStage)
 *
 * 逆向确认 (CA.exe EN v18): `CStage::OnPacket` (0x00502AE1) case 17 → `0x004FB0BE`
 *
 * 包体解码与落点（逐条来自 0x004FB0BE 反汇编，读序与调用序一一对应）:
 *   1. Decode1 (slot)          —— 本机玩家槽 0/1；下面 2~5 四个字段都按这个槽写入
 *   2. Decode1 (ladderGrade)   —— CLadderInfo +3s+7  天梯段位        (`sub_6E473B`)
 *   3. Decode4 (ladderPoint)   —— CLadderInfo +3s+1  天梯数值 A      (`sub_6E4757`)
 *   4. Decode4 (**lucci**)     —— CUserDynamicInfo +3s+12 **Lucci 金币** (`sub_6E4E23`)
 *   5. Decode4 (userField30)   —— CUserDynamicInfo +3s+30 未知        (`sub_6E4B37`)
 *   6. Decode4 (catalogRevision) —— CShopItemMan::CheckRevision (`sub_670CBE`)
 *   7. Decode4 (rev2) / 8. Decode4 (rev3)                          (`sub_670D66` / `sub_670E05`)
 *   9. DecodeStr (msg) / 10. Decode4 (bonusCount)
 *   - 阶段切换: 校验后 `SetStage(14)` (0x0068DC53) 切到 CShopStageNew,
 *     并置 `[CShopConnectionMan + 0x10] = 1` (IsInShop = true)
 */
fun EnterShopStage(
    shopVersion: Int = ShopCatalog.CATALOG_HASH,
    lucci: Int = 0,
) = PKT {
    Encode1(0)          //  1 slot (本机玩家槽)
    Encode1(0)          //  2 天梯段位
    Encode4(0)          //  3 天梯数值 A
    Encode4(lucci)      //  4 Lucci 金币 (CUserDynamicInfo +3s+12)
    Encode4(0)          //  5 CUserDynamicInfo +3s+30
    Encode4(shopVersion)//  6 catalogRevision
    Encode4(0)          //  7 rev2
    Encode4(0)          //  8 rev3
    EncodeStr("")       //  9 msg
    Encode4(0)          // 10 bonusCount
}
