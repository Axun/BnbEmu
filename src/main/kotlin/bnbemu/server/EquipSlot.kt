package bnbemu.server

/**
 * 装备槽位定义 (CA.exe CUserInventory / CUserInventoryMan):
 *
 * 客户端穿戴与背包槽位映射机制 (CA.exe 0x0067601D CUserInventoryMan::ReadEquippedInventory):
 * - 15 (0x0F): 基础角色卡 / 形象卡槽位 (Character / Avatar Card Slot)
 *   对应 itemId 如: 1 (Dao / 皮蛋), 5 (Boz / Bazzi / 宝宝), 4 (Marid / 蓝蓝) 等。
 *   - 在 C→S op 273 (ChangeEquipReq) 中变更该槽位时，表示玩家切换了出战角色形象卡；
 *   - 在 S→C op 198 (UserInventory) 及 S→C op 73 (UserInfoResult) 中用来向客户端下发当前生效的角色卡。
 * - 13 (0x0D): 道具栏 / 扩展背包槽位 (ItemBag, 如 itemId 10006)
 * - 4 (0x04): 角色进阶 / 装扮卡槽位 (CharDeco, 如 itemId 2082/2083 蓝妹妹 LuxuryMarid)
 */
object EquipSlot {
    /** 基础角色卡 / 形象卡槽位 (CA.exe 0x0F, itemId 如 1=Dao, 5=Boz 等) */
    const val CHARACTER = 15

    /** 道具栏 / 扩展背包槽位 (如 10006 ItemBag) */
    const val ITEM_BAG = 13

    /** 角色进阶 / 装扮卡槽位 (如 2082/2083 蓝妹妹) */
    const val CHAR_DECO = 4

    /** 客户端用来表示「这件物品只在背包里, 没有装备槽」的伪槽位 (0xFF) */
    const val BAG = 0xFF
}
