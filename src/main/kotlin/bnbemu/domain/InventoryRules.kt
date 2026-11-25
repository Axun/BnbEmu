package bnbemu.domain

import bnbemu.data.ItemDefCatalog

/** 每组装备的槽位数量 (客户端 24 个) */
const val EQUIP_SLOTS_PER_GROUP = 24

/** 消耗品单格最大堆叠上限（CA.exe `sub_66817F`: `cur + buy >= 10000` 弹 AmountLimitOver） */
const val MAX_STACK_COUNT = 9999

/**
 * 可堆叠分类（`sub_677435`）：喇叭 [ItemCategory.SPEAKER]、道具袋 [ItemCategory.ITEM_BAG]，
 * 以及 34/37/40/45/46/47/49 等道具卡分类（基础表未收录，仅客户端映射里出现）。
 */
private val STACKABLE_CATEGORIES = setOf(
    1,
    ItemCategory.ITEM_BAG,
    ItemCategory.SPEAKER,
    34, 37, 40, 45, 46, 47, 49,
)

/**
 * 背包与装备槽位领域规则。
 */
object InventoryRules {
    /**
     * 物品分类是否可堆叠，镜像 CA.exe `sub_677435` (`0x00677435`) + `sub_6788D0` (`0x006788D0`)。
     * 包含喇叭(15)、道具卡(13/34/37/40/45/46/47/49)等消耗品。
     */
    fun isStackableCategory(category: Int): Boolean = category in STACKABLE_CATEGORIES

    /** 判断物品是否可堆叠（优先查 ItemDefCatalog，缺省时可回落传入的商品分类）。 */
    fun isStackableItem(itemId: Int, fallbackCategory: Int? = null): Boolean {
        val cat = ItemDefCatalog.find(itemId)?.category ?: fallbackCategory ?: return false
        return isStackableCategory(cat)
    }

    /**
     * category → 装备大类, CA.exe `sub_67884E` (`0x0067884E`) 的逐 case 镜像。
     * 0 表示该分类不可穿戴 (客户端 `sub_65C81A` 第一步就 `if (type == 0) return 0`)。
     *
     * 大类 → 复合槽位区间 = `24 × 大类 … +23`：
     * 4=96~119、5=120~143（纸娃娃）、6=144~167（进阶卡）、7=168~191（道具）、
     * 8=192~215（角色卡）、**9=216~239（装扮/背景/名牌）**、**10=240~263（特效/水泡）**。
     */
    fun equipGroupOfCategory(category: Int): Int = when (category) {
        ItemCategory.CHARACTER_MODEL -> 8
        1 -> 8
        2, 9, 20, 33, 36, 41 -> 7
        ItemCategory.FANFARE, 5, 6, ItemCategory.SLOT_DECO, ItemCategory.ID_DECO, 28, ItemCategory.LADDER_DECO -> 9
        ItemCategory.CHAR_DECO, ItemCategory.BOMB -> 10
        ItemCategory.ADVANCE_CARD -> 6
        ItemCategory.AVATAR -> 5
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
