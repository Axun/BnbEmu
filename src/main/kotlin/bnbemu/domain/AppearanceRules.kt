package bnbemu.domain

import bnbemu.data.ItemDefCatalog

/**
 * 外观相关字段的取值规则（供各下发包共用）。
 */
object AppearanceRules {

    /**
     * 已穿戴名牌的样式序号（0 = 未穿戴）。
     *
     * 名牌 = [ItemCategory.ID_DECO]（`Icon/ItemIDdeco_C###`），客户端 `CA.exe 0x00694232`
     * 按 `"IDDeco/IDDeco_C%03d"` 载入贴图，序号即 `ItemDef.skinId`；
     * 基础表样本 `2441 category=18 Icon/ItemIDdeco_C001 skinId=1`。
     *
     * 装备大类 = 9（`sub_67884E`: category 10/18 → 大类 9，槽位 216~239），
     * 因此从 [PlayerProfile.equips] 里按物品定义分类筛即可，不必解析槽位。
     */
    fun namePlateStyle(profile: PlayerProfile): Int =
        profile.equips.values
            .asSequence()
            .mapNotNull { ItemDefCatalog.find(it) }
            .firstOrNull { it.category == ItemCategory.ID_DECO }
            ?.skinId ?: 0

    /**
     * 等级内的经验千分比 0..999。
     *
     * 等级模型 ([bnbemu.service.PlayerService.settleMatch]) 里 exp 本身就是「本级内的千分比」
     * (满 1000 升级)，客户端经验环按 `60 * v / 999` 画 (`sub_5056EB`)，与结算包
     * (op 100 / GameResult E2 EXP%) 同一量纲。
     */
    fun expPercent(profile: PlayerProfile): Int = profile.exp.coerceIn(0, 999)
}
