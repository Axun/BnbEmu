package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.DEFAULT_USER_INVENTORY_ITEMS
import bnbemu.packet.UserInventory
import bnbemu.packet.inventoryEntries

// C→S 264 背包資料查詢 (CLobbyStage 0x00513226 構造空包)
//
// 回包用 S→C op 198（CUserInventoryMan::ReadEquippedInventory 0x67601D 全量重建背包+装备），
// 不能用 op 320：320 是商城专用的「按商品定义塞一件商品」，定义查的是商城商品管理器
// (dword_993D58)，在大厅/频道里取不到，包会被静默丢弃（见 packet/UserItemList.kt）。
//
// ⚠️ 这里同样是「全量真相」：漏掉 273 记录下来的装备（背景/旗帜/边框…）会让客户端
//    在打开背包时把它们判成已脱下，所以必须走和进频道同一套 inventoryEntries。
val UserItemListReq = PKTHandler { c ->
    val user = c.users.firstOrNull()
    val bag = user?.items.orEmpty()
    val equips = user?.equips.orEmpty()
    val inventory = inventoryEntries(DEFAULT_USER_INVENTORY_ITEMS, bag, equips)
    if (bag.isNotEmpty() || equips.isNotEmpty()) {
        println(
            "[UserItemListReq] op 198 清单: 背包 ${bag.size} 件 + 装备 ${equips.size} 件" +
                " → 共 ${inventory.size} 条"
        )
    }
    c send UserInventory(listOf(inventory, inventory))
}
