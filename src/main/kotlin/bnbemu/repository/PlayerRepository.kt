package bnbemu.repository

import bnbemu.domain.PlayerProfile

/**
 * 玩家档案仓储接口。
 * 现阶段由 [InMemoryPlayerRepository] 实现，后续可直接替换为 SQLite 实现。
 */
interface PlayerRepository : AutoCloseable {
    fun findByUserId(userId: Int): PlayerProfile?
    fun findByUsername(username: String): PlayerProfile?
    fun findByCharName(charName: String): PlayerProfile?
    fun save(profile: PlayerProfile)
    fun getOrCreateByUsername(username: String): PlayerProfile
    fun getOrCreateByUserId(userId: Int, defaultCharName: String = "Player"): PlayerProfile

    /** 将挂起的持久化变更刷盘（内存实现为空操作，SQLite 实现可在此做 WAL checkpoint/commit）。 */
    fun flush() {}

    /** 关闭底层存储连接并释放资源。 */
    override fun close() {}
}
