package bnbemu

import bnbemu.domain.CharacterRules
import bnbemu.domain.CharacterType
import bnbemu.domain.EquipSlot
import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.domain.StarterKit
import bnbemu.netty.toBA
import bnbemu.packet.battle.GameResultSlotSnapshot
import bnbemu.packet.battle.GameStartSlotConfig
import bnbemu.packet.user.UserEquipEntry
import bnbemu.packet.user.UserInventoryEntry
import bnbemu.packet.user.inventoryEntries

/**
 * 抓包基准样本 —— **只存在于测试侧**。
 *
 * 原先散落在生产包里的抓包默认常量与样本数据全部收口于此，生产签名要求调用方显式
 * 传入真实玩家或房间状态，这里仅保留给逐字节 / 字段偏移的回归断言使用。
 */
object CaptureFixtures {

    /** 抓包账号 kcworms1 的账号数值 (passport 0x2E 同值) */
    const val CAPTURE_USER_ID = 7215

    /** 抓包账号 kcworms1 的角色名 */
    const val CAPTURE_CHAR_NAME = "ffdf"

    /** 抓包账号 kcworms1 的 4B 会话令牌 (encodeName("ffdf")) */
    val CAPTURE_SESSION_TOKEN: ByteArray = "f0ae35f3".toBA()

    /** 名片/装备回归测试用默认装备条目 (2082 进阶卡 @ CHAR_DECO) */
    val DEFAULT_USER_EQUIPS = listOf(
        UserEquipEntry(
            itemId = CharacterRules.MARID_EVO_CARD_ITEM_ID,
            slot = EquipSlot.CHAR_DECO,
            param1 = 1,
            param2 = 0,
        ),
    )

    /** 初始穿戴条目清单 (基于 StarterKit.defaultInventory) */
    fun defaultUserInventoryItems(): List<UserInventoryEntry> =
        inventoryEntries(StarterKit.defaultInventory())

    val DEFAULT_USER_INVENTORY_ITEMS: List<UserInventoryEntry> = defaultUserInventoryItems()

    /** `cap/practice_s2c_op112_167B_0.bin` 里的固定出生点排列 [2,1,6,0,3,4,5,7] */
    val OP112_SPAWN_ORDER = intArrayOf(2, 1, 6, 0, 3, 4, 5, 7)

    /**
     * `cap/practice_s2c_op112_167B_0.bin` 的开局名册:
     * 8 槽全 Boz(5) / 出生点照 [OP112_SPAWN_ORDER] / 槽 0 玩家队、其余 AI 队。
     */
    val OP112_SLOT_CONFIGS: List<GameStartSlotConfig> =
        (0 until MAX_SESSION_SLOTS).map { i ->
            GameStartSlotConfig(
                slotIdx = i,
                spawnPos = OP112_SPAWN_ORDER[i],
                bomber = CharacterType.Boz.value,
                team = if (i == 0) 0 else 8,
            )
        }

    /**
     * 练习房结算页的槽位快照: 槽 0 真人 (等级 5, 战绩 0/0/0) + 槽 1..7 AI (难度档 1)。
     * 与 `cap/practice_s2c_op150_*.bin` 的槽位布局同构。
     */
    val OP150_PRACTICE_SLOTS: List<GameResultSlotSnapshot> =
        listOf(GameResultSlotSnapshot.Human()) + List(7) { GameResultSlotSnapshot.Ai(1) }
}
