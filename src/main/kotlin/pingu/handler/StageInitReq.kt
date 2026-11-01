package pingu.handler

import pingu.netty.PKTHandler
import pingu.packet.ItemDef
import pingu.packet.QuestConfigTable
import pingu.packet.SessionInfoResult
import pingu.packet.SessionList
import pingu.packet.Unk227
import pingu.packet.Unk254
import pingu.packet.Unk293
import pingu.packet.Unk302
import pingu.packet.Unk310
import pingu.packet.UserSimpleInfoResult

/**
 * C→S 阶段就绪 / 任务配置请求 (Opcode 78, C→S)
 *
 * 逆向确认 (CA.exe, 2026-11-01):
 * 1. 客户端在切换至频道/大厅阶段 (State 11) 时，于 CMainSystem::RequestStageInit (0x0068D80F) 发出此包。
 * 2. 发包前调用 CFxContext::Clear() (0x0068919D) 重置上下文，发包内容为空载荷。
 * 3. 真实服务器在此触发点按线序 (enter-channel.pcap seq=64~97) 下发进频道批次的整套核心数据:
 *    [Unk310, Unk302, Unk254, SessionList, Unk293, QuestConfigTable,
 *     UserSimpleInfoResult, SessionInfoResult, Unk227, ItemDef, Unk310, Unk302]
 */
val StageInitReq = PKTHandler { c ->
    c.send(
        Unk310(), Unk302(),
        Unk254(),
        SessionList(),
        Unk293(), QuestConfigTable(),
        UserSimpleInfoResult(), SessionInfoResult(),
        Unk227(), ItemDef(),
        Unk310(), Unk302(),
    )
}
