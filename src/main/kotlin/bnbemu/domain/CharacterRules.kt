package bnbemu.domain

/**
 * 角色映射与进阶规则（单一事实源）。
 *
 * 统一收口原分散在 CreateSessionReq / Room / SelectBomberReq / UserInfoReq
 * 与 packet/user/UserInventory.kt 的角色规则。
 */
object CharacterRules {
    const val MARID_EVO_CARD_ITEM_ID = 2082

    /**
     * 解析选角实际生效的角色 ID：
     * 客户端在选角界面点击进阶角色时会上报基础角色 4（蓝蓝 Marid），
     * 当玩家持有并装备 2082 进阶卡（或未传入档案的规则查询）时映射为进阶形态 10（蓝妹妹 LuxuryMarid）。
     */
    fun resolveEffectiveBomber(requestedBomber: Int, profile: PlayerProfile? = null): Int =
        if (requestedBomber == 4 && (profile == null || profile.hasEquippedItem(MARID_EVO_CARD_ITEM_ID))) {
            10
        } else {
            requestedBomber
        }

    /**
     * 角色 (bomber id) → 模型名（供 op 60 cat=3 表情/动作定义同步用）。
     */
    fun bomberModelName(bomber: Int): String = when (bomber) {
        1 -> "Diz"
        2 -> "Moss"
        3 -> "Melvin"
        4, 10 -> "Marid"
        5 -> "Boz"
        6 -> "Ike"
        7 -> "Plunk"
        else -> "Dao"
    }
}
