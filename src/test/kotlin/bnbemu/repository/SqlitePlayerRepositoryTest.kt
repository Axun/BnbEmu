package bnbemu.repository

import bnbemu.ServerConfig
import bnbemu.data.ShopCatalogData
import bnbemu.domain.CharacterRules
import bnbemu.domain.EquipChangeEntry
import bnbemu.domain.EquipSlot
import bnbemu.domain.PlayerProfile
import bnbemu.domain.StarterKit
import bnbemu.domain.UserItemEntry
import bnbemu.service.PlayerService
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SqlitePlayerRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        ServerConfig.resetToDefaults()
    }

    @AfterEach
    fun tearDown() {
        PlayerService.shutdown()
        PlayerService.repository = InMemoryPlayerRepository()
        ServerConfig.resetToDefaults()
    }

    @Test
    fun `starts empty without capture seeds and allocates distinct ids from 10000`() {
        val dbFile = tempDir.resolve("nested/save/bnb.db")
        val repo = SqlitePlayerRepository(dbFile.toString())
        try {
            assertTrue(Files.isRegularFile(dbFile))
            assertNull(repo.findByUserId(7215))
            assertNull(repo.findByUsername("kcworms1"))
            assertNull(repo.findByUsername("kcworm1"))

            val alice = repo.getOrCreateByUsername("Alice")
            val bob = repo.getOrCreateByUsername("Bob")
            assertEquals(10000, alice.userId)
            assertEquals(10001, bob.userId)
            assertEquals(alice, repo.findByUsername("ALICE"))
            assertEquals(alice, repo.findByCharName("alice"))
            assertEquals(
                CharacterRules.MARID_EVO_CARD_ITEM_ID,
                alice.equips[StarterKit.CHAR_DECO_COMPOSITE_SLOT],
            )
        } finally {
            repo.close()
        }
    }

    @Test
    fun `in-memory database works across operations`() {
        val repo = SqlitePlayerRepository(":memory:")
        try {
            assertNull(repo.findByUserId(10000))
            val p = repo.getOrCreateByUsername("solo_player")
            assertEquals(10000, p.userId)
            assertNotNull(repo.findByUserId(10000))
        } finally {
            repo.close()
        }
    }

    @Test
    fun `persists profile mutations, inventory, equips and match settlement across restarts`() {
        val dbPath = tempDir.resolve("persist.db").toString()

        // 1. 首轮启动：注册玩家并经 PlayerService 发生充值、购买、换装、选角色与对局结算
        val repo1 = SqlitePlayerRepository(dbPath)
        PlayerService.repository = repo1

        val alice = PlayerService.loginOrRegister("Alice")
        val aliceId = alice.userId
        PlayerService.chargeCash(aliceId, 8000)
        val bomb655 = ShopCatalogData.findByProdId(930000655)!!
        val speakerBundle = ShopCatalogData.findByProdId(1000806)!!
        assertTrue(PlayerService.purchaseCatalogItem(aliceId, bomb655).success)
        assertTrue(PlayerService.purchaseCatalogItem(aliceId, speakerBundle).success)

        // 把 2082 进阶卡脱回背包，同时穿戴 Bomb 655 (大类10, 子槽14 -> 复合槽位 10*24+14 = 254)
        PlayerService.applyEquipChange(
            aliceId,
            listOf(
                EquipChangeEntry(EquipSlot.BAG, CharacterRules.MARID_EVO_CARD_ITEM_ID),
                EquipChangeEntry(254, bomb655.itemId),
            ),
        )
        PlayerService.selectBomber(aliceId, 5)
        PlayerService.settleMatch(
            userId = aliceId,
            won = true,
            draw = false,
            expGained = 1250,
            lucciGained = 300,
        )

        val expectedLucci = alice.lucci
        val expectedLevel = alice.level
        val expectedExp = alice.exp

        repo1.flush()
        repo1.close()

        // 2. 重开全新仓储实例，验证全量字段与背包/穿戴状态完整还原
        val repo2 = SqlitePlayerRepository(dbPath)
        try {
            val reloaded = repo2.findByUserId(aliceId)
            assertNotNull(reloaded)
            reloaded!!
            assertEquals("Alice", reloaded.username)
            assertEquals("Alice", reloaded.charName)
            assertEquals(expectedLevel, reloaded.level)
            assertEquals(expectedExp, reloaded.exp)
            assertEquals(expectedLucci, reloaded.lucci)
            assertEquals(6000, reloaded.cash) // 8000 - 2000
            assertEquals(1, reloaded.win)
            assertEquals(0, reloaded.draw)
            assertEquals(0, reloaded.lose)
            assertEquals(5, reloaded.bomber)

            // 穿戴与背包状态均一致
            assertEquals(bomb655.itemId, reloaded.equips[254])
            assertNull(reloaded.equips[StarterKit.CHAR_DECO_COMPOSITE_SLOT])
            assertEquals(1, reloaded.items.single { it.itemId == CharacterRules.MARID_EVO_CARD_ITEM_ID }.count)
            assertEquals(6, reloaded.items.single { it.itemId == 10008 }.count)

            // 新注册玩家继续单调递增，不与已有 ID 冲突
            val charlie = repo2.getOrCreateByUsername("Charlie")
            assertNotEquals(aliceId, charlie.userId)
            assertEquals(aliceId + 1, charlie.userId)
        } finally {
            repo2.close()
        }
    }

    @Test
    fun `persists custom UserItemEntry fields and ignores unknown JSON fields on load`() {
        val dbFile = tempDir.resolve("compat.db")
        val repo1 = SqlitePlayerRepository(dbFile.toString())
        val created = repo1.getOrCreateByUsername("compat_user")
        val p = PlayerProfile(
            userId = created.userId,
            username = created.username,
            charName = created.charName,
            motto = "Custom Motto",
            favorite = "Dao",
            inventory = listOf(
                UserItemEntry(
                    itemId = 999001,
                    count = 3,
                    equipSlot = null,
                    expireAt = 1799999999000L,
                    arg1 = 42,
                )
            ),
        )
        repo1.save(p)
        repo1.close()

        // 模拟旧存档/未来扩展存档中存在未知 JSON 字段，验证 ignoreUnknownKeys 生效
        val rawDb = Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        try {
            transaction(rawDb) {
                exec(
                    """
                    UPDATE players
                    SET inventory_json = '{"schemaVersion":2,"futureHeader":true,"items":[{"itemId":999001,"count":3,"expireAt":1799999999000,"arg1":42,"unknownAttr":"kept_safe"}]}'
                    WHERE user_id = ${p.userId}
                    """.trimIndent()
                )
            }
        } finally {
            TransactionManager.closeAndUnregister(rawDb)
        }

        val repo2 = SqlitePlayerRepository(dbFile.toString())
        try {
            val loaded = repo2.findByUserId(p.userId)!!
            assertEquals("Custom Motto", loaded.motto)
            assertEquals("Dao", loaded.favorite)
            val item = loaded.items.single()
            assertEquals(999001, item.itemId)
            assertEquals(3, item.count)
            assertEquals(1799999999000L, item.expireAt)
            assertEquals(42, item.arg1)
        } finally {
            repo2.close()
        }
    }

    @Test
    fun `getOrCreateByUserId persists profile and PlayerService shutdown flushes cleanly`() {
        val dbPath = tempDir.resolve("bind_shutdown.db").toString()
        val repo1 = SqlitePlayerRepository(dbPath)
        PlayerService.repository = repo1

        val bound = PlayerService.bindConnection(8888, "GuestHero")
        assertEquals(8888, bound.userId)
        assertEquals("GuestHero", bound.charName)
        PlayerService.chargeLucci(8888, 500)
        PlayerService.shutdown()

        val repo2 = SqlitePlayerRepository(dbPath)
        try {
            val reloaded = repo2.findByUserId(8888)
            assertNotNull(reloaded)
            assertEquals("GuestHero", reloaded!!.charName)
            assertEquals(bound.lucci, reloaded.lucci)
        } finally {
            repo2.close()
        }
    }
}
