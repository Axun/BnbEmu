package bnbemu.repository

import bnbemu.domain.PlayerProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内存版玩家档案仓储（收编原 PassportServer.ACCOUNTS 与 PlayerWallet/Bag/Equips/Character）。
 *
 * 预设保留抓包账号 (kcworms1 / kcworm1)，确保回归与抓包回放测试 100% 通过。
 */
class InMemoryPlayerRepository : PlayerRepository {
    private val byUserId = ConcurrentHashMap<Int, PlayerProfile>()
    private val byUsername = ConcurrentHashMap<String, PlayerProfile>()
    private val nextUserId = AtomicInteger(10000)

    init {
        seedDefaults()
    }

    private fun seedDefaults() {
        save(PlayerProfile(userId = 7215, username = "kcworms1", charName = "ffdf"))
        save(PlayerProfile(userId = 7307, username = "kcworm1", charName = "kcworm1"))
    }

    override fun findByUserId(userId: Int): PlayerProfile? = byUserId[userId]

    override fun findByUsername(username: String): PlayerProfile? =
        byUsername[username.lowercase()]

    override fun findByCharName(charName: String): PlayerProfile? =
        byUserId.values.firstOrNull { it.charName.equals(charName, ignoreCase = true) }

    override fun save(profile: PlayerProfile) {
        byUserId[profile.userId] = profile
        byUsername[profile.username.lowercase()] = profile
    }

    override fun getOrCreateByUsername(username: String): PlayerProfile {
        val key = username.lowercase()
        byUsername[key]?.let { return it }
        synchronized(this) {
            byUsername[key]?.let { return it }
            var id = nextUserId.getAndIncrement()
            while (byUserId.containsKey(id)) {
                id = nextUserId.getAndIncrement()
            }
            val created = PlayerProfile(
                userId = id,
                username = username,
                charName = username,
            )
            save(created)
            return created
        }
    }

    override fun getOrCreateByUserId(userId: Int, defaultCharName: String): PlayerProfile {
        byUserId[userId]?.let { return it }
        synchronized(this) {
            byUserId[userId]?.let { return it }
            val resolvedName = defaultCharName.ifBlank { "Player" }
            val created = PlayerProfile(
                userId = userId,
                username = "user_$userId",
                charName = resolvedName,
            )
            save(created)
            return created
        }
    }

    /** 仅用于测试重置内存状态 */
    fun reset() {
        byUserId.clear()
        byUsername.clear()
        nextUserId.set(10000)
        seedDefaults()
    }
}
