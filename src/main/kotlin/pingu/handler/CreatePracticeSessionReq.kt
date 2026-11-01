package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.PKTHandler
import pingu.packet.PracticeColor
import pingu.packet.PracticeRoomInfo
import pingu.packet.PracticeSetPlayer
import pingu.packet.PracticeSlot103
import pingu.packet.PracticeSlot104
import pingu.packet.PracticeSlot114
import pingu.packet.PracticeSlotReady
import pingu.packet.PracticeUserInventory
import pingu.packet.UserItemList
import pingu.packet.PracticeUnk423
import pingu.packet.RoomList
import pingu.packet.SessionInfoResult
import pingu.packet.SessionSimpleInfo
import pingu.packet.Unk113
import pingu.packet.UserSimpleInfoResult
import pingu.server.*

// ============================================================================
// 进练习房 (C→S op 75) — 在练习房界面点"开始"时发出
// ============================================================================
// 载体 (tst.pcap stream4 sq=79, 14B) = E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)
//   E2(0)           房号/练习房标识
//   E2(0)           模式/地图? (练习房固定 0)
//   E1(0)           ?
//   E1(1)           ?
//   E1(5)           bomber (角色, 与默认一致)
//   E1(15)          ?
//   E4(66)          地图 id? (练习房地图, 待钉死)
// 服务器连发整套房间设置回应 (seq 顺序, 见 Practice.kt 注释):
//   [87 RoomList, 83 SessionSimpleInfo, 4(13B), 4(65B), 113, 100(78B), 108,
//    106×8, 每槽(114,104,105,103), 198, 423]
// 之后客户端自动跟发 297/302, 并再发 op 92 触发游戏开始 (见 StartGameReq.kt)。
// 玩家注册进 Room slot 0 (leader), 其余 7 槽设为 AI, 以便 AI 自动行动广播 (Room.BC) 能送达。
// server = C???::OnCreatePracticeSession (原始名称未知, 绑定见 EN_18_recv.yaml)
val CreatePracticeSessionReq = PKTHandler { c ->
    Decode2 // 0 房号
    Decode2 // 0
    Decode1 // 0
    Decode1 // 1
    Decode1 // 5 bomber
    Decode1 // 15
    Decode4 // 66 地图 id

    // 玩家 (op 7 已把 c.users[0] 填好) 放 slot 0, 其余 7 槽为 AI
    val player = c.users.firstOrNull()
    if (player != null) {
        Room.slots[0].apply {
            this.user = player
            state = FLAG_CHIEF
            bomber = 5
            color = 0
        }
        for (i in 1..7) {
            Room.slots[i].apply {
                this.user = null
                isAI = true
                bomber = 5
                color = 7
            }
        }
    }

    c.send(
        RoomList(),
        SessionSimpleInfo(),
        UserSimpleInfoResult(), SessionInfoResult(),
        Unk113(),
        PracticeRoomInfo(),
        PracticeColor(),
    )
    // 106×8 (sq=208~229)
    repeat(8) { slot ->
        c.send(PracticeSlotReady(slot))
    }
    // 每槽交错发送 [114, 104, 105, 103] (槽 7 无 103), 与真实线序一致
    repeat(8) { slot ->
        c.send(
            PracticeSlot114(slot),
            PracticeSlot104(slot),
            PracticeSetPlayer(slot),
        )
        if (slot < 7) c.send(PracticeSlot103(slot = slot + 1, isAI = true, aiLevel = slot + 1, flag = 1))
    }
    c.send(PracticeUserInventory())
    c.send(PracticeUnk423())
}
