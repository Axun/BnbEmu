package bnbemu.service

import bnbemu.domain.DEFAULT_LUCCI
import bnbemu.domain.EquipChangeEntry
import bnbemu.domain.EquipChangeResult
import bnbemu.domain.EquipSlot
import bnbemu.domain.MAX_CASH
import bnbemu.domain.PlayerProfile
import bnbemu.domain.UserItemEntry
import bnbemu.netty.ClientSocket
import bnbemu.repository.InMemoryPlayerRepository
import bnbemu.repository.PlayerRepository

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
            profile.cash = minOf(profile.cash + amount, MAX_CASH)
            repository.save(profile)
            return profile.cash
        }
    }

    /** 记录购买物品入包（暂保留原有去重语义，后续步骤接入扣款与堆叠） */
    fun buyItem(userId: Int, itemId: Int, count: Int = 1): Boolean {
        val profile = repository.findByUserId(userId) ?: return false
        synchronized(profile) {
            if (profile.mutableItems.any { it.itemId == itemId }) {
                return false
            }
            profile.mutableItems += UserItemEntry(itemId = itemId, count = count)
            repository.save(profile)
            return true
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

            for (change in changes) {
                if (change.itemId == 0) continue
                if (change.equipSlot == EquipSlot.BAG) {
                    val equippedSlot = profile.mutableEquips.entries
                        .find { it.value == change.itemId }
                        ?.key
                    if (equippedSlot != null) {
                        profile.mutableEquips.remove(equippedSlot)
                        unequippedSlots += equippedSlot to change.itemId
                        equipChanged = true
                    }
                    if (profile.mutableItems.none { it.itemId == change.itemId }) {
                        profile.mutableItems += UserItemEntry(itemId = change.itemId)
                        addedToBag += change.itemId
                        bagChanged = true
                    }
                } else {
                    profile.mutableEquips[change.equipSlot] = change.itemId
                    if (profile.mutableItems.removeIf { it.itemId == change.itemId }) {
                        removedFromBag += change.itemId
                        bagChanged = true
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

    /** 统一写入当前选中角色 */
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
            repository.save(profile)
            return profile
        }
    }
}

val ClientSocket.player: PlayerProfile?
    get() = PlayerService.currentPlayer(this)

/**
 * 当前连线的金币余额。
 * 进频道/进商城可能各自新开连线, 尚未绑定玩家时回落 [DEFAULT_LUCCI]。
 */
val ClientSocket.lucci: Int
    get() = player?.lucci ?: DEFAULT_LUCCI

/** 当前连线的点券 (NX Cash) 余额。 */
val ClientSocket.cash: Int
    get() = player?.cash ?: 0
