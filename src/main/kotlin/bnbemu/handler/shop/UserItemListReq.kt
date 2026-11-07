package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.DEFAULT_USER_INVENTORY_ITEMS
import bnbemu.packet.UserInventory
import bnbemu.packet.bagInventoryEntries

// C→S 264 背包資料查詢 (CLobbyStage 0x00513226 構造空包)
//
// 回包用 S→C op 198（CUserInventoryMan::ReadEquippedInventory 0x67601D 全量重建背包+装备），
// 不能用 op 320：320 是商城专用的「按商品定义塞一件商品」，定义查的是商城商品管理器
// (dword_993D58)，在大厅/频道里取不到，包会被静默丢弃（见 packet/UserItemList.kt）。
val UserItemListReq = PKTHandler { c ->
    val bag = c.users.firstOrNull()?.items.orEmpty()
    val inventory = DEFAULT_USER_INVENTORY_ITEMS + bagInventoryEntries(bag)
    c send UserInventory(listOf(inventory, inventory))
}
