package bnbemu.domain

/**
 * 装备槽位定义 (CA.exe CUserInventory / CUserInventoryMan):
 *
 * - 4 (0x04): 角色进阶 / 装扮卡槽位 (CharDeco, 如 itemId 2082 蓝妹妹 LuxuryMarid)
 * - 0xFF: 背包伪槽位（只在背包向量中，不占装备槽）
 */
object EquipSlot {
    /** 角色进阶 / 装扮卡槽位 (如 2082 蓝妹妹) */
    const val CHAR_DECO = 4

    /** 客户端用来表示「这件物品只在背包里, 没有装备槽」的伪槽位 (0xFF) */
    const val BAG = 0xFF
}
