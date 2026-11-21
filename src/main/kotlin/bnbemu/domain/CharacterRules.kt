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
     * 角色 (bomber id) → 角色卡 (itemId, 模型令牌)。
     *
     * 全工程唯一一份映射: op 198、op 60、op 73 名片、op 200 均走这里。
     * 实证 (S→C op 198 抓包): bomber 0 → item 1 "Dao", bomber 5 → item 5 "Boz"。
     * 蓝蓝 (4) 与蓝妹妹 (10) 走进阶卡 2082 (category 16 / skinId 10 / targetCharId 4)。
     */
    fun bomberCharCard(bomber: Int): Pair<Int, String> = when (bomber) {
        1 -> 2 to "Diz"
        2 -> 3 to "Moss"
        3 -> 4 to "Melvin"
        4, 10 -> 2082 to "Marid"
        5 -> 5 to "Boz"
        6 -> 6 to "Ike"
        7 -> 7 to "Plunk"
        else -> 1 to "Dao"
    }
}
