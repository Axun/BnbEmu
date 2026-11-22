package bnbemu.handler.shop

import bnbemu.domain.StarterKit
import bnbemu.netty.PKTHandler
import bnbemu.packet.user.UserInventory
import bnbemu.packet.user.inventoryEntries
import bnbemu.service.player

// C→S 264 背包資料查詢 (CLobbyStage 0x00513226 構造空包)
//
// 回包用 S→C op 198（CUserInventoryMan::ReadEquippedInventory 0x67601D 全量重建背包+装备），
// 不能用 op 320：320 是商城专用的「按商品定义塞一件商品」，定义查的是商城商品管理器
// (dword_993D58)，在大厅/频道里取不到，包会被静默丢弃（见 packet/UserItemList.kt）。
//
// ⚠️ 这里同样是「全量真相」：直接从玩家统一库存读取，保证与进频道完全一致。
val UserItemListReq = PKTHandler { c ->
    val player = c.player
    val rawInventory = player?.inventory ?: StarterKit.defaultInventory()
    val inventory = inventoryEntries(rawInventory)
    if (player != null) {
        println(
            "[UserItemListReq] op 198 清单: 背包 ${player.items.size} 件 + 装备 ${player.equips.size} 件" +
                " → 共 ${inventory.size} 条"
        )
    }
    c send UserInventory(listOf(inventory, inventory))
}
