package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C EatItemResult (EN op 143 / 0x8F, CA.exe 0x0055A64B -> 0x0054BE87)
// EN 载荷:
//   Encode1(slotId)
//   Encode1(gridX)
//   Encode1(gridY)
//   Encode2(itemSeq)   -- CGameMsgCache::0x00539313 校验并移除待确认拾取项, 同时传给 CBomber::OnEatItem (+0x180)
//   Encode1(itemType)
//   [若道具分类为 0x61/0x63 金币袋, 0x0054BF1C 继续读取: Encode1(unk1) + Encode1(unk2) + Encode4(lucciDelta) + Encode4(total1) + Encode4(total2)]
//   (普通道具在 0x0054BF51 之后不再读取 CInPacket, 附带 14B 尾字段对所有道具均安全兼容)
fun EatItemResult(
    slotId: Int,
    gridXOrItemId: Int,
    gridYOrItemType: Int,
    itemSeq: Int = 0,
    itemType: Int = 0
) = PKT {
    Encode1(slotId)
    Encode1(gridXOrItemId)
    Encode1(gridYOrItemType)
    Encode2(itemSeq)
    Encode1(itemType)
    Encode1(1)
    Encode1(0)
    Encode4(10)
    Encode4(0)
    Encode4(0)
}
