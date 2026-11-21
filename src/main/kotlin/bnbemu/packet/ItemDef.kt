package bnbemu.packet

import bnbemu.data.ItemDefCatalog
import bnbemu.data.ItemDefEntry
import bnbemu.netty.PKT

/**
 * 进频道批次回应: 物品唯一定义表 (Opcode 60, S→C)
 *
 * 先传玩家当前角色，按角色重建 (角色卡 itemId/skinId + 所有 cat=3
 * 表情/动作定义的模型名)。传错不会更改当前选中的角色类型。
 * 主数据加载与查询已收口至 [ItemDefCatalog]。
 */
fun ItemDef(bomber: Int = 0): PKT = PKT {
    val blob = ItemDefCatalog.blobFor(bomber)
    Encode4(blob.size)
    EncodeBuffer(blob)
}

fun ItemDef(items: List<ItemDefEntry>?) = PKT {
    val blob = if (items == null) ItemDefCatalog.CACHED_BLOB else ItemDefCatalog.encode(items)
    Encode4(blob.size)
    EncodeBuffer(blob)
}
