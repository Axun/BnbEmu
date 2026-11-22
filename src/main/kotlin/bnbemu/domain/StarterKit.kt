package bnbemu.domain

/**
 * 新建玩家档案的初始物品与穿戴配置（单一事实源）。
 *
 * 所有新存档在创建时通过 [defaultInventory] 注入蓝妹妹进阶卡 (2082) 的穿戴状态，
 * 之后所有发包仅读取玩家真实库存，不再在发包时临时拼接。
 */
object StarterKit {
    const val CHAR_DECO_COMPOSITE_SLOT = 6 * EQUIP_SLOTS_PER_GROUP + EquipSlot.CHAR_DECO // 0x94 (148)

    fun defaultInventory(): List<UserItemEntry> = listOf(
        UserItemEntry(
            itemId = CharacterRules.MARID_EVO_CARD_ITEM_ID,
            count = 1,
            equipSlot = CHAR_DECO_COMPOSITE_SLOT,
        ),
    )
}
