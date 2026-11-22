package bnbemu.domain

import bnbemu.domain.CharacterType

/** 模拟器初始默认金币 (Lucci); 所有金币同步统一取此值, 避免各处硬编码漂移。 */
const val DEFAULT_LUCCI = 1234567

/**
 * 点券 (NX Cash) 的模拟器侧保守上限。
 * 客户端 op 201 (sub_5CFE29 -> sub_6E4AFE) 写 Cash 数组时**没有**上限校验
 * (只有 op 122 的 sub_6E4E23 会 min(value, sub_5062E9)), 这里仍沿用后者给出的
 * 3,000,000 作为封顶, 避免无限累加导致数值/显示异常。
 */
const val MAX_CASH = 3_000_000

/** 客户端默认金币上限（`sub_5062E9`: 3,000,000 × 倍率，倍率为 0 时按 1 计）。 */
const val MAX_LUCCI = 3_000_000

const val DEFAULT_LEVEL = 5

data class UserItemEntry(
    val itemId: Int,
    val count: Int = 1,
    val equipSlot: Int? = null,
    val expireAt: Long? = null,
    val arg1: Int = 0,
) {
    val isEquipped: Boolean
        get() = equipSlot != null && equipSlot != EquipSlot.BAG
}

data class EquipChangeEntry(
    val equipSlot: Int,
    val itemId: Int,
)

data class EquipChangeResult(
    val bagChanged: Boolean,
    val equipChanged: Boolean,
    val unequippedSlots: List<Pair<Int, Int>>,
    val addedToBag: Set<Int>,
    val removedFromBag: Set<Int>,
)

/**
 * 玩家档案（持久化聚合根，单一事实源）。
 *
 * 与网络连接 ([bnbemu.netty.ClientSocket]) 完全解耦：
 * 不管客户端开登录连接、频道连接还是商城连接，均通过 userId 读写同一份档案。
 */
class PlayerProfile(
    val userId: Int,
    val username: String,
    var charName: String = username,
    var level: Int = DEFAULT_LEVEL,
    var exp: Int = 0,
    var lucci: Int = DEFAULT_LUCCI,
    var cash: Int = 0,
    var win: Int = 0,
    var draw: Int = 0,
    var lose: Int = 0,
    var motto: String = "PopTag Forever!",
    var favorite: String = "Bazzi",
    /** 玩家当前选中的角色 */
    var bomber: Int = CharacterType.Dao.value,
    inventory: List<UserItemEntry> = StarterKit.defaultInventory(),
) {
    internal val mutableInventory: MutableList<UserItemEntry> = inventory.toMutableList()

    /**
     * 玩家统一库存（背包 + 已穿戴装备的单一事实源）。
     * `equipSlot == null` 表示在背包中；非空表示已穿戴在对应复合槽位。
     * 只读视图，所有变更须经 [bnbemu.service.PlayerService]。
     */
    val inventory: List<UserItemEntry> get() = mutableInventory

    /**
     * 背包未穿戴物品视图（兼容旧调用点；底层与 [inventory] 共享单一事实源）。
     */
    val items: List<UserItemEntry>
        get() = mutableInventory.filter { !it.isEquipped }

    /**
     * 已穿戴的复合槽位 → itemId 映射视图（兼容旧调用点；底层与 [inventory] 共享单一事实源）。
     */
    val equips: Map<Int, Int>
        get() = buildMap {
            mutableInventory.forEach { entry ->
                val slot = entry.equipSlot
                if (slot != null && slot != EquipSlot.BAG && entry.itemId != 0) {
                    put(slot, entry.itemId)
                }
            }
        }

    fun hasItem(itemId: Int): Boolean = mutableInventory.any { it.itemId == itemId }

    fun hasEquippedItem(itemId: Int): Boolean =
        mutableInventory.any { it.itemId == itemId && it.isEquipped }
}
