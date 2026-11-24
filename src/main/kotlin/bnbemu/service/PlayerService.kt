package bnbemu.service

import bnbemu.data.ShopCurrency
import bnbemu.data.ShopItemEntry
import bnbemu.domain.CharacterRules
import bnbemu.domain.DEFAULT_LUCCI
import bnbemu.domain.EquipChangeEntry
import bnbemu.domain.EquipChangeResult
import bnbemu.domain.EquipSlot
import bnbemu.domain.InventoryRules
import bnbemu.domain.MAX_CASH
import bnbemu.domain.MAX_LUCCI
import bnbemu.domain.MAX_STACK_COUNT
import bnbemu.domain.PlayerProfile
import bnbemu.domain.UserItemEntry
import bnbemu.netty.ClientSocket
import bnbemu.repository.InMemoryPlayerRepository
import bnbemu.repository.PlayerRepository
import bnbemu.server.Slot
import org.tinylog.kotlin.Logger

enum class PurchaseStatus {
    SUCCESS,
    PLAYER_NOT_FOUND,
    INSUFFICIENT_LUCCI,
    INSUFFICIENT_CASH,
    UNSUPPORTED_CURRENCY,
    ALREADY_OWNED,
    AMOUNT_LIMIT_EXCEEDED,
}

data class PurchaseResult(
    val status: PurchaseStatus,
    val lucci: Int = 0,
    val cash: Int = 0,
    val itemCount: Int = 0,
) {
    val success: Boolean get() = status == PurchaseStatus.SUCCESS
}

/**
 * 玩家档案应用服务层。
 *
 * 所有 Handler 对玩家持久化状态（点券、金币、角色、背包、装备、战绩）的读写
 * 均通过本服务进行，避免各处裸改集合，为后续接入 SQLite 持久化提供统一边界。
 */
object PlayerService {
    @Volatile
    var repository: PlayerRepository = InMemoryPlayerRepository()

    @Volatile
    private var activeUserId: Int = 7215

    /** 当前活跃（最近登录/连线）的玩家档案 */
    val activeProfile: PlayerProfile
        get() = repository.findByUserId(activeUserId)
            ?: repository.getOrCreateByUserId(7215, "ffdf")

    fun loginOrRegister(username: String): PlayerProfile {
        val profile = repository.getOrCreateByUsername(username)
        activeUserId = profile.userId
        return profile
    }

    fun bindConnection(userId: Int, charName: String = ""): PlayerProfile {
        val profile = repository.getOrCreateByUserId(userId, charName.ifBlank { "Player" })
        activeUserId = profile.userId
        return profile
    }

    fun registerProfile(profile: PlayerProfile): PlayerProfile {
        repository.save(profile)
        return profile
    }

    fun findByUserId(userId: Int): PlayerProfile? = repository.findByUserId(userId)

    fun findByUsername(username: String): PlayerProfile? = repository.findByUsername(username)

    fun findByCharName(charName: String): PlayerProfile? = repository.findByCharName(charName)

    fun currentPlayer(c: ClientSocket): PlayerProfile? =
        c.playerId?.let { repository.findByUserId(it) }

    /** 充值点券 (NX Cash), 返回充值后的余额 (封顶 [MAX_CASH]) */
    fun chargeCash(userId: Int, amount: Int): Int {
        val profile = repository.getOrCreateByUserId(userId)
        synchronized(profile) {
            profile.cash = minOf(profile.cash + amount.coerceAtLeast(0), MAX_CASH)
            repository.save(profile)
            return profile.cash
        }
    }

    /** 充值金币 (Lucci), 返回充值后的余额 (封顶 [MAX_LUCCI]) */
    fun chargeLucci(userId: Int, amount: Int): Int {
        val profile = repository.getOrCreateByUserId(userId)
        synchronized(profile) {
            profile.lucci = minOf(profile.lucci + amount.coerceAtLeast(0), MAX_LUCCI)
            repository.save(profile)
            return profile.lucci
        }
    }

    /** 记录购买物品入包（免费/直接入包便捷入口，走统一的堆叠与去重规则） */
    fun buyItem(userId: Int, itemId: Int, count: Int = 1): Boolean =
        purchaseItem(
            userId = userId,
            itemId = itemId,
            count = count,
            price = 0,
            currency = ShopCurrency.LUCCI,
        ).success

    /** 按商城目录条目执行完整购买（校验持有/堆叠上限、扣除 Lucci/Cash、写入或累加背包）。 */
    fun purchaseCatalogItem(userId: Int, entry: ShopItemEntry): PurchaseResult =
        purchaseItem(
            userId = userId,
            itemId = entry.itemId,
            count = entry.count,
            price = entry.price,
            currency = entry.currency,
            stackable = InventoryRules.isStackableItem(entry.itemId, fallbackCategory = entry.category),
        )

    /** 购买物品统一写方法：余额校验、扣款、非堆叠去重、消耗品堆叠。 */
    fun purchaseItem(
        userId: Int,
        itemId: Int,
        count: Int = 1,
        price: Int = 0,
        currency: ShopCurrency = ShopCurrency.LUCCI,
        stackable: Boolean = InventoryRules.isStackableItem(itemId),
    ): PurchaseResult {
        val profile = repository.findByUserId(userId)
            ?: return PurchaseResult(PurchaseStatus.PLAYER_NOT_FOUND)
        val grantedCount = count.coerceAtLeast(1)
        val unitPrice = price.coerceAtLeast(0)
        synchronized(profile) {
            val inv = profile.mutableInventory
            val existingIdx = inv.indexOfFirst { it.itemId == itemId }
            if (!stackable && existingIdx != -1) {
                return PurchaseResult(PurchaseStatus.ALREADY_OWNED, lucci = profile.lucci, cash = profile.cash)
            }
            val existingCount = if (existingIdx != -1) inv[existingIdx].count else 0
            if (stackable && existingCount + grantedCount > MAX_STACK_COUNT) {
                return PurchaseResult(PurchaseStatus.AMOUNT_LIMIT_EXCEEDED, lucci = profile.lucci, cash = profile.cash)
            }
            when (currency) {
                ShopCurrency.LUCCI -> {
                    if (profile.lucci < unitPrice) {
                        return PurchaseResult(PurchaseStatus.INSUFFICIENT_LUCCI, lucci = profile.lucci, cash = profile.cash)
                    }
                    profile.lucci -= unitPrice
                }
                ShopCurrency.CASH -> {
                    if (profile.cash < unitPrice) {
                        return PurchaseResult(PurchaseStatus.INSUFFICIENT_CASH, lucci = profile.lucci, cash = profile.cash)
                    }
                    profile.cash -= unitPrice
                }
                ShopCurrency.GCOUPON -> {
                    return PurchaseResult(PurchaseStatus.UNSUPPORTED_CURRENCY, lucci = profile.lucci, cash = profile.cash)
                }
            }
            val newCount = if (stackable && existingIdx != -1) {
                val updated = inv[existingIdx].copy(count = existingCount + grantedCount)
                inv[existingIdx] = updated
                updated.count
            } else {
                inv += UserItemEntry(itemId = itemId, count = grantedCount)
                grantedCount
            }
            repository.save(profile)
            return PurchaseResult(
                status = PurchaseStatus.SUCCESS,
                lucci = profile.lucci,
                cash = profile.cash,
                itemCount = newCount,
            )
        }
    }

    /**
     * 统一应用客户端 C→S op 273 上报的装备穿脱与背包同步变更。
     */
    fun applyEquipChange(userId: Int, changes: List<EquipChangeEntry>): EquipChangeResult {
        val profile = repository.findByUserId(userId)
            ?: return EquipChangeResult(
                bagChanged = false,
                equipChanged = false,
                unequippedSlots = emptyList(),
                addedToBag = emptySet(),
                removedFromBag = emptySet(),
            )
        synchronized(profile) {
            var bagChanged = false
            var equipChanged = false
            val unequippedSlots = mutableListOf<Pair<Int, Int>>()
            val addedToBag = linkedSetOf<Int>()
            val removedFromBag = linkedSetOf<Int>()
            val inv = profile.mutableInventory

            for (change in changes) {
                if (change.itemId == 0) continue
                if (change.equipSlot == EquipSlot.BAG) {
                    val equippedIdx = inv.indexOfFirst {
                        it.itemId == change.itemId && it.isEquipped
                    }
                    if (equippedIdx != -1) {
                        val oldSlot = inv[equippedIdx].equipSlot!!
                        inv[equippedIdx] = inv[equippedIdx].copy(equipSlot = null)
                        unequippedSlots += oldSlot to change.itemId
                        addedToBag += change.itemId
                        equipChanged = true
                        bagChanged = true
                    } else if (inv.none { it.itemId == change.itemId && !it.isEquipped }) {
                        inv += UserItemEntry(itemId = change.itemId, equipSlot = null)
                        addedToBag += change.itemId
                        bagChanged = true
                    }
                } else {
                    // 若目标槽位已有其他物品，先退回背包
                    for (i in inv.indices) {
                        if (inv[i].equipSlot == change.equipSlot && inv[i].itemId != change.itemId) {
                            inv[i] = inv[i].copy(equipSlot = null)
                            bagChanged = true
                        }
                    }
                    val targetIdx = inv.indexOfFirst {
                        it.equipSlot == change.equipSlot || it.itemId == change.itemId
                    }
                    if (targetIdx != -1) {
                        val existing = inv[targetIdx]
                        if (!existing.isEquipped) {
                            removedFromBag += change.itemId
                            bagChanged = true
                        }
                        inv[targetIdx] = existing.copy(itemId = change.itemId, equipSlot = change.equipSlot)
                    } else {
                        inv += UserItemEntry(itemId = change.itemId, equipSlot = change.equipSlot)
                    }
                    equipChanged = true
                }
            }

            if (bagChanged || equipChanged) {
                repository.save(profile)
            }
            return EquipChangeResult(
                bagChanged = bagChanged,
                equipChanged = equipChanged,
                unequippedSlots = unequippedSlots,
                addedToBag = addedToBag,
                removedFromBag = removedFromBag,
            )
        }
    }

    /** 统一写入当前选中角色。 */
    fun selectBomber(userId: Int, bomber: Int): PlayerProfile? {
        val profile = repository.findByUserId(userId) ?: return null
        synchronized(profile) {
            profile.bomber = bomber
            repository.save(profile)
            return profile
        }
    }

    /** 对局结算写回玩家档案（胜/负/平、金币、经验与升级） */
    fun settleMatch(
        userId: Int,
        won: Boolean,
        draw: Boolean,
        expGained: Int,
        lucciGained: Int,
        maxExpPerLevel: Int = 1000,
    ): PlayerProfile? {
        val profile = repository.findByUserId(userId) ?: return null
        return settleMatch(
            profile = profile,
            won = won,
            draw = draw,
            expGained = expGained,
            lucciGained = lucciGained,
            maxExpPerLevel = maxExpPerLevel,
        )
    }

    /**
     * 对局结算写回指定 [PlayerProfile] 实例。
     * 若该玩家存在于仓储中会同步调用 [PlayerRepository.save] 落盘。
     */
    fun settleMatch(
        profile: PlayerProfile,
        won: Boolean,
        draw: Boolean,
        expGained: Int,
        lucciGained: Int,
        maxExpPerLevel: Int = 1000,
    ): PlayerProfile {
        synchronized(profile) {
            when {
                draw -> profile.draw += 1
                won -> profile.win += 1
                else -> profile.lose += 1
            }
            profile.lucci += lucciGained.coerceAtLeast(0)
            var totalExp = profile.exp + expGained.coerceAtLeast(0)
            while (totalExp >= maxExpPerLevel) {
                totalExp -= maxExpPerLevel
                profile.level += 1
            }
            profile.exp = totalExp
            if (repository.findByUserId(profile.userId) != null) {
                repository.save(profile)
            }
            return profile
        }
    }

    /** 优雅停机：刷盘并关闭玩家档案仓储。 */
    fun shutdown() {
        runCatching { repository.flush() }
            .onFailure { Logger.error(it) { "[PlayerService] 刷新档案仓储失败: ${it.message}" } }
        runCatching { repository.close() }
            .onFailure { Logger.error(it) { "[PlayerService] 关闭档案仓储失败: ${it.message}" } }
    }
}

val ClientSocket.player: PlayerProfile?
    get() = PlayerService.currentPlayer(this)

val Slot.player: PlayerProfile?
    get() = playerId?.let { PlayerService.findByUserId(it) }

/**
 * 当前连线的金币余额。
 * 进频道/进商城可能各自新开连线, 尚未绑定玩家时回落 [DEFAULT_LUCCI]。
 */
val ClientSocket.lucci: Int
    get() = player?.lucci ?: DEFAULT_LUCCI

/** 当前连线的点券 (NX Cash) 余额。 */
val ClientSocket.cash: Int
    get() = player?.cash ?: 0
