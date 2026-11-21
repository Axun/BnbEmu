package bnbemu

import bnbemu.constants.CharacterType
import bnbemu.constants.MAX_SESSION_SLOTS
import bnbemu.packet.battle.GameResultSlotSnapshot
import bnbemu.packet.battle.GameStartSlotConfig

/**
 * 抓包基准样本 —— **只存在于测试侧**。
 *
 * 这两张表原先把抓包里的房间状态写成生产函数的默认参数，等于让包体「假装」描述了一个
 * 真实房间（谁参战 / 谁在哪个出生点 / 结算页哪几槽是 AI）。生产签名现在要求调用方显式
 * 传入真实状态，抓包原值集中放这里，仅供逐字节 / 字段偏移的回归断言使用。
 */
object CaptureFixtures {

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
