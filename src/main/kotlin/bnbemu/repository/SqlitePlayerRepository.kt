package bnbemu.repository

import bnbemu.ServerConfig
import bnbemu.domain.PlayerProfile
import bnbemu.domain.UserItemEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.json
import org.tinylog.kotlin.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val storageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
internal data class PersistedItemEntry(
    val itemId: Int,
    val count: Int = 1,
    val equipSlot: Int? = null,
    val expireAt: Long? = null,
    val arg1: Int = 0,
) {
    fun toDomain(): UserItemEntry = UserItemEntry(
        itemId = itemId,
        count = count,
        equipSlot = equipSlot,
        expireAt = expireAt,
        arg1 = arg1,
    )

    companion object {
        fun fromDomain(entry: UserItemEntry): PersistedItemEntry = PersistedItemEntry(
            itemId = entry.itemId,
            count = entry.count,
            equipSlot = entry.equipSlot,
            expireAt = entry.expireAt,
            arg1 = entry.arg1,
        )
    }
}

@Serializable
internal data class PersistedInventory(
    val schemaVersion: Int = 1,
    val items: List<PersistedItemEntry> = emptyList(),
)

internal object PlayersTable : Table("players") {
    val userId = integer("user_id")
    val username = varchar("username", 64)
    val usernameKey = varchar("username_key", 64).uniqueIndex()
    val charName = varchar("char_name", 64).index()
    val level = integer("level")
    val exp = integer("exp")
    val lucci = integer("lucci")
    val cash = integer("cash")
    val win = integer("win")
    val draw = integer("draw")
    val lose = integer("lose")
    val motto = varchar("motto", 256)
    val favorite = varchar("favorite", 64)
    val bomber = integer("bomber")
    val inventory = json<PersistedInventory>("inventory_json", storageJson)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

private data class ProfileSnapshot(
    val userId: Int,
    val username: String,
    val usernameKey: String,
    val charName: String,
    val level: Int,
    val exp: Int,
    val lucci: Int,
    val cash: Int,
    val win: Int,
    val draw: Int,
    val lose: Int,
    val motto: String,
    val favorite: String,
    val bomber: Int,
    val inventory: PersistedInventory,
)

/**
 * 基于 Exposed 1.x + SQLite + kotlinx.serialization 的单机玩家档案仓储。
 *
 * - 标量字段存储为关系列，统一库存 ([PlayerProfile.inventory]) 通过 `exposed-json` 序列化为 JSON。
 * - 内存一级缓存 (Identity Map) + Write-Through 即时落盘，保证跨连线共享同一 [PlayerProfile] 实例。
 */
class SqlitePlayerRepository(
    dbPath: String = ServerConfig.current.database.path,
) : PlayerRepository {
    private val dbLock = Any()
    private val byUserId = ConcurrentHashMap<Int, PlayerProfile>()
    private val byUsername = ConcurrentHashMap<String, PlayerProfile>()
    private val nextUserId = AtomicInteger(10000)
    private val closed = AtomicBoolean(false)

    private val isMemory: Boolean
    private val keepAliveConnection: Connection?
    private val db: Database

    init {
        val trimmed = dbPath.trim()
        isMemory = trimmed == ":memory:"
        val jdbcUrl = when {
            isMemory -> {
                val memName = "bnbemu_${UUID.randomUUID().toString().replace("-", "")}"
                "jdbc:sqlite:file:$memName?mode=memory&cache=shared"
            }
            trimmed.startsWith("jdbc:sqlite:", ignoreCase = true) -> trimmed
            else -> {
                val resolved = Path.of(trimmed).toAbsolutePath().normalize()
                resolved.parent?.let { Files.createDirectories(it) }
                "jdbc:sqlite:$resolved"
            }
        }

        keepAliveConnection = if (isMemory) {
            DriverManager.getConnection(jdbcUrl).also { applyPragmas(it, memory = true) }
        } else {
            null
        }

        db = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC",
            setupConnection = { conn -> applyPragmas(conn, memory = isMemory) },
        )
        TransactionManager.managerFor(db)?.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        synchronized(dbLock) {
            transaction(db) {
                SchemaUtils.create(PlayersTable)
                val rows = PlayersTable.selectAll().toList()
                for (row in rows) {
                    val profile = rowToProfile(row)
                    byUserId[profile.userId] = profile
                    byUsername[profile.username.lowercase()] = profile
                }
                val maxId = rows.maxOfOrNull { it[PlayersTable.userId] } ?: 9999
                nextUserId.set(maxOf(10000, maxId + 1))
            }
        }
        Logger.info("[SqlitePlayerRepository] 已初始化存档库 ($jdbcUrl), 已加载 ${byUserId.size} 个玩家档案")
    }

    private fun applyPragmas(conn: Connection, memory: Boolean) {
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA busy_timeout=5000;")
            if (!memory) {
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA synchronous=NORMAL;")
            }
            stmt.execute("PRAGMA foreign_keys=ON;")
        }
    }

    private fun rowToProfile(row: ResultRow): PlayerProfile = PlayerProfile(
        userId = row[PlayersTable.userId],
        username = row[PlayersTable.username],
        charName = row[PlayersTable.charName],
        level = row[PlayersTable.level],
        exp = row[PlayersTable.exp],
        lucci = row[PlayersTable.lucci],
        cash = row[PlayersTable.cash],
        win = row[PlayersTable.win],
        draw = row[PlayersTable.draw],
        lose = row[PlayersTable.lose],
        motto = row[PlayersTable.motto],
        favorite = row[PlayersTable.favorite],
        bomber = row[PlayersTable.bomber],
        inventory = row[PlayersTable.inventory].items.map(PersistedItemEntry::toDomain),
    )

    override fun findByUserId(userId: Int): PlayerProfile? = byUserId[userId]

    override fun findByUsername(username: String): PlayerProfile? =
        byUsername[username.lowercase()]

    override fun findByCharName(charName: String): PlayerProfile? =
        byUserId.values.firstOrNull { it.charName.equals(charName, ignoreCase = true) }

    override fun save(profile: PlayerProfile) {
        val snapshot = synchronized(profile) {
            ProfileSnapshot(
                userId = profile.userId,
                username = profile.username,
                usernameKey = profile.username.lowercase(),
                charName = profile.charName,
                level = profile.level,
                exp = profile.exp,
                lucci = profile.lucci,
                cash = profile.cash,
                win = profile.win,
                draw = profile.draw,
                lose = profile.lose,
                motto = profile.motto,
                favorite = profile.favorite,
                bomber = profile.bomber,
                inventory = PersistedInventory(
                    schemaVersion = 1,
                    items = profile.inventory.map(PersistedItemEntry::fromDomain),
                ),
            )
        }
        synchronized(dbLock) {
            check(!closed.get()) { "SqlitePlayerRepository 已关闭，无法写入存档" }
            val now = System.currentTimeMillis()
            transaction(db) {
                PlayersTable.upsert(PlayersTable.userId) {
                    it[userId] = snapshot.userId
                    it[username] = snapshot.username
                    it[usernameKey] = snapshot.usernameKey
                    it[charName] = snapshot.charName
                    it[level] = snapshot.level
                    it[exp] = snapshot.exp
                    it[lucci] = snapshot.lucci
                    it[cash] = snapshot.cash
                    it[win] = snapshot.win
                    it[draw] = snapshot.draw
                    it[lose] = snapshot.lose
                    it[motto] = snapshot.motto
                    it[favorite] = snapshot.favorite
                    it[bomber] = snapshot.bomber
                    it[inventory] = snapshot.inventory
                    it[updatedAt] = now
                }
            }
            byUserId[snapshot.userId] = profile
            byUsername[snapshot.usernameKey] = profile
        }
    }

    override fun getOrCreateByUsername(username: String): PlayerProfile {
        val key = username.lowercase()
        byUsername[key]?.let { return it }
        synchronized(dbLock) {
            byUsername[key]?.let { return it }
            var id = nextUserId.getAndIncrement()
            while (byUserId.containsKey(id)) {
                id = nextUserId.getAndIncrement()
            }
            val defaults = ServerConfig.current.player
            val created = PlayerProfile(
                userId = id,
                username = username,
                charName = username,
                level = defaults.defaultLevel,
                lucci = defaults.defaultLucci,
                cash = defaults.defaultCash,
            )
            save(created)
            return created
        }
    }

    override fun getOrCreateByUserId(userId: Int, defaultCharName: String): PlayerProfile {
        byUserId[userId]?.let { return it }
        synchronized(dbLock) {
            byUserId[userId]?.let { return it }
            val resolvedName = defaultCharName.ifBlank { "Player" }
            val defaults = ServerConfig.current.player
            val created = PlayerProfile(
                userId = userId,
                username = "user_$userId",
                charName = resolvedName,
                level = defaults.defaultLevel,
                lucci = defaults.defaultLucci,
                cash = defaults.defaultCash,
            )
            save(created)
            return created
        }
    }

    override fun flush() {
        synchronized(dbLock) {
            if (closed.get() || isMemory) return
            transaction(db) {
                exec("PRAGMA wal_checkpoint(TRUNCATE);")
            }
        }
    }

    override fun close() {
        synchronized(dbLock) {
            if (!closed.compareAndSet(false, true)) return
            if (!isMemory) {
                runCatching {
                    transaction(db) {
                        exec("PRAGMA wal_checkpoint(TRUNCATE);")
                    }
                }.onFailure {
                    Logger.warn(it) { "[SqlitePlayerRepository] WAL checkpoint 失败: ${it.message}" }
                }
            }
            runCatching { keepAliveConnection?.close() }
                .onFailure { Logger.warn(it) { "[SqlitePlayerRepository] 关闭内存连接失败: ${it.message}" } }
            runCatching { TransactionManager.closeAndUnregister(db) }
                .onFailure { Logger.warn(it) { "[SqlitePlayerRepository] 注销数据库实例失败: ${it.message}" } }
        }
    }
}
