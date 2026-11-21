package bnbemu.domain

import bnbemu.data.item.ItemDefCatalog

/** 每组装备的槽位数量 (客户端 24 个) */
const val EQUIP_SLOTS_PER_GROUP = 24

/**
 * 背包与装备槽位领域规则。
 */
object InventoryRules {
    /**
     * category → 装备大类, CA.exe `sub_67884E` (`0x0067884E`) 的逐 case 镜像。
     * 0 表示该分类不可穿戴 (客户端 `sub_65C81A` 第一步就 `if (type == 0) return 0`)。
     */
    fun equipGroupOfCategory(category: Int): Int = when (category) {
        1, 3 -> 8
        2, 9, 20, 33, 36, 41 -> 7
        4, 5, 6, 10, 18, 28, 29 -> 9
        12, 14 -> 10
        16 -> 6
        19 -> 5
        42 -> 4
        else -> 0
    }

    /**
     * 装备条目的「客户端定位键」= (装备大类, 子槽位)。
     *
     * 用大类参与键值是因为 type 由物品分类推导: 同一个 subSlot 在不同大类里是不同槽位
     * (例如道具栏 cat 13 的 subSlot 13 与装扮背景 cat 10 的 subSlot 13 互不影响)。
     */
    fun equipSlotKey(itemId: Int, slot: Int): Pair<Int, Int> =
        equipGroupOfCategory(ItemDefCatalog.find(itemId)?.category ?: -1) to slot

    /** 按 itemId 查询装备大类（未知物品返回 -1） */
    fun groupOfItem(itemId: Int): Int {
        val cat = ItemDefCatalog.find(itemId)?.category ?: return -1
        return equipGroupOfCategory(cat)
    }
}
