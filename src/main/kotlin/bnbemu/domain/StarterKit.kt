package bnbemu.domain

/**
 * 新建玩家档案的初始物品与穿戴配置（单一事实源）。
 *
 * 所有新存档在创建时通过 [defaultInventory] 注入蓝妹妹进阶卡 (2082) 的穿戴状态，
 * 之后所有发包仅读取玩家真实库存，不再在发包时临时拼接。
 */
object StarterKit {
    /** 进阶卡装备大类（客户端 `sub_67884E(16) == 6`），复合槽位 = 24×大类 + 子槽 */
    private const val ADVANCE_CARD_GROUP = 6

    /** 2082 蓝妹妹的复合槽位 0x94 (148) = 24×6 + 4 */
    const val CHAR_DECO_COMPOSITE_SLOT = ADVANCE_CARD_GROUP * EQUIP_SLOTS_PER_GROUP + EquipSlot.CHAR_DECO

    fun defaultInventory(): List<UserItemEntry> = listOf(
        UserItemEntry(
            itemId = CharacterRules.MARID_EVO_CARD_ITEM_ID,
            count = 1,
            equipSlot = CHAR_DECO_COMPOSITE_SLOT,
        ),
    )
}
