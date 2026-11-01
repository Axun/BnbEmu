package pingu.packet

import pingu.netty.PKT

// ============================================================================
// S→C op 274: 玩家持有/裝備道具槽位更新 (進頻道握手推送 #4/#5, seq=109/112, 15B ×2 連發)
// ============================================================================
// 逆向確認:
//   CA.exe 0x00503761 (CStage::OnPacket) -> 0x004FCCBF.
//   處理類: TSingleton<CUserItemMan> (0x00993D58) 與 TSingleton<CUserInventoryMan> (0x00993E68).
//   欄位結構:
//     Encode1: slotType (0=槽位0, 1=槽位1)
//     Encode4: itemId (道具 ID, 在 CUserItemMan::FindItem 中檢索)
//     Encode4: quantity / count (數量)
//     Encode4: expireDays / hours (有效期天數/小時: 0x004FCDA2 imul eax, 24)
//     Encode1: flag1
//     Encode1: flag2
//   隨後調用 0x00656805 (CUserInventoryMan::UpdateSlot) 刷新玩家背包/紙娃娃槽位 UI.
fun UserItemUpdate(
    slotType: Int = 0,
    itemId: Int = 1,
    quantity: Int = 0,
    expireHours: Int = 0,
    flag1: Int = 7,
    flag2: Int = 0
) = PKT {
    Encode1(slotType)
    Encode4(itemId)
    Encode4(quantity)
    Encode4(expireHours)
    Encode1(flag1)
    Encode1(flag2)
}
