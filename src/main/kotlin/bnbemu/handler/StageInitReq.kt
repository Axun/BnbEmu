package bnbemu.handler

import bnbemu.netty.PKTHandler
import bnbemu.packet.ItemDef
import bnbemu.packet.bomberCharCard
import bnbemu.packet.MyPageInfo
import bnbemu.packet.QuestConfigTable
import bnbemu.packet.SessionInfoResult
import bnbemu.packet.Unk293
import bnbemu.packet.UserMarketItemList
import bnbemu.packet.UserMarketMyItemList
import bnbemu.packet.UserSimpleInfoResult

/**
 * C→S 阶段就绪 / 任务配置请求 (Opcode 78, C→S)
 *
 * 逆向确认 (CA.exe, 2026-11-01):
 * 1. 客户端在切换至频道/大厅阶段 (State 11) 时，于 CMainSystem::RequestStageInit (0x0068D80F) 发出此包。
 * 2. 发包前调用 CFxContext::Clear() (0x0068919D) 重置上下文，发包内容为空载荷。
 * 3. 进频道批次中由各请求分别触发回包:
 *    - op 302 (UserStageSyncReq) -> op 310 (UserMarketItemList), op 302 (UserMarketMyItemList)
 *    - op 310 (UserMapListReq) -> op 254 (UserMapInfo), op 72 (SessionList)
 *    - op 78 (StageInitReq) -> [Unk293, QuestConfigTable, UserSimpleInfoResult, SessionInfoResult,
 *                               MyPageInfo, ItemDef, UserMarketItemList, UserMarketMyItemList]
 */
val StageInitReq = PKTHandler { c ->
    // op 60 先按当前角色生成 (角色卡 itemId/skinId + 所有 cat=3 模型名都在里面),
    val bomber = c.users.firstOrNull()?.bomber ?: 0
    val (charItemId, charToken) = bomberCharCard(bomber)
    println("[StageInitReq] op 60 物品定义表: bomber=$bomber → 角色卡 item $charItemId ($charToken), cat=3 模型同步")
    c.send(
        Unk293(), QuestConfigTable(),
        UserSimpleInfoResult(), SessionInfoResult(),
        MyPageInfo(), ItemDef(bomber),
        UserMarketItemList(), UserMarketMyItemList(),
    )
}
