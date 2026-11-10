package bnbemu.server

import bnbemu.netty.ClientSocket
import bnbemu.packet.EnterSessionResult
import bnbemu.packet.PracticeSelectMap
import bnbemu.packet.PracticeSetPlayer
import bnbemu.packet.room.SetPlayer
import bnbemu.packet.PracticeSetSlotAI
import bnbemu.packet.PracticeSlot104
import bnbemu.packet.PracticeSlot114
import bnbemu.packet.PracticeSlotReady
import bnbemu.packet.RoomList
import bnbemu.packet.SessionInfoResult
import bnbemu.packet.Unk113
import bnbemu.packet.UserSimpleInfoResult
import bnbemu.packet.WelcomeNotice

/**
 * 整条「进房间」S→C 推送链（练习房基准）。
 *
 * 原来内联在 `handler/CreateSessionReq.kt`（C→S op 75 的回应），
 * 2026-11-08 抽出来给「对局结束回房间」复用：`Room.backToRoom` 发完 S→C op 155 之后，
 * 客户端虽然已经切回 stage 12，但房间对象/会话是空的 —— 不补这条链，房主位、座位、
 * 房间名、选图都会是降级状态。
 *
 * 线序与 tst.pcap stream4 一致：
 *   [87 RoomList, 83 EnterSessionResult, 4(13B), 4(65B), 113, 100(78B), 108,
 *    106×8, 每槽(114, 104, 105) + 下一槽的 103, WelcomeNotice]
 */
fun sendRoomEntryChain(c: ClientSocket) {
    val user = c.users.firstOrNull()
    c.send(
        RoomList(),
        // 进房结果 (op 83): 房型必须原样回显, 客户端 0x0068626F 存进 CFxContext[0x570]。
        EnterSessionResult(sessionId = Room.sessionId, sessionType = Room.sessionType),
        UserSimpleInfoResult(), SessionInfoResult(),
        Unk113(),
        SetPlayer(slotId = 0, user = user),
        PracticeSelectMap(),
    )
    // 106×8
    repeat(8) { slot -> c.send(PracticeSlotReady(slot)) }
    // 每槽先发 [114, 104, 105]，随后补「下一槽」的 AI 槽属性包 103 (槽号 = 当前组 + 1)。
    // 最后一组 (槽 7) 之后没有槽 8 可补，故不发 103；槽 7 自己的 103 已由第 6 组发出。
    // 只有槽 0 是真人，槽 1..7 全是 AI，op 103 从不用于真人槽。
    repeat(8) { slot ->
        c.send(
            PracticeSlot114(slot),
            PracticeSlot104(slot),
            PracticeSetPlayer(slot),
        )
        if (slot < 7) c.send(PracticeSetSlotAI(slot = slot + 1, isAI = true, aiLevel = slot + 1, flag = 1))
    }
    c.send(WelcomeNotice())
}
