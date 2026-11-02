package bnbemu.packet

import bnbemu.netty.PKT

/**
 * S->C op 17: 进入商城阶段 (EnterShopStage)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 触发入口:
 *   - CStage::OnPacket 0x00502B72 (cmp eax, 0x11 -> 17) 调用 0x004FB0BE
 * - 封包解码结构:
 *   1. Decode1(): mode/index (0)
 *   2. Decode1(): subMode (0)
 *   3. Decode4(): f3 (0)
 *   4. Decode4(): f4 (0)
 *   5. Decode4(): f5 (0)
 *   6. Decode4(): catalogRevision (0x75D92163, 传给 CShopItemMan::CheckRevision)
 *   7. Decode4(): rev2 (0)
 *   8. Decode4(): rev3 (0)
 *   9. DecodeStr(): msg ("")
 *   10. Decode4(): bonusCount (0)
 * - 阶段切换:
 *   - 校验当前目标阶段为 14 (0x0E):
 *     调用 CMainSystem::Instance().SetStage(14) (0x0068DC53),
 *     销毁大厅并构造 CShopStageNew (0x005D5FBF),
 *     并将 [CShopConnectionMan + 0x10] 置为 1 (IsInShop = true).
 */
fun EnterShopStage(
    shopVersion: Int = ShopCatalog.CATALOG_HASH
) = PKT {
    Encode1(0)
    Encode1(0)
    Encode4(0)
    Encode4(0)
    Encode4(0)
    Encode4(shopVersion)
    Encode4(0)
    Encode4(0)
    EncodeStr("")
    Encode4(0)
}
