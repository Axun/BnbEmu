package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.PKTHandler
import pingu.packet.PracticeAiLevel
import pingu.packet.PracticeBoard
import pingu.packet.PracticeSelectMap
import pingu.packet.PracticeStartInfo
import pingu.packet.room.DoStartGame
import pingu.packet.room.LaunchGameStage
import pingu.server.Room

// ============================================================================
// 进练习房后的"开始游戏"请求 (C→S op 92) — 练习房内点"开始"后自动发出
// ============================================================================
// 载体 (tst.pcap stream4 sq=91, 17B body) = E4(0xAF) E2(28) E1(0) E4(172) E1(0) E1(1) E4(0)
//   （字段语义未钉死; E4(172) 与 AI 道具 op 172 同值, 0xAF 疑似地图/模式标识）
// 服务器回游戏开始序列:
//   [111 AILevelChange, 112 SelectMap, 100 PracticeStartInfo, 136 Board, 170 LaunchGameStage, 171 DoStartGame]
// 之后客户端发 149 EventItemReq×7, 服务器开始周期性广播 op 172 ItemThrow / op 139 (AI 自动行动,
// 见 Room.kt aiTick)。
// server = C???::OnStartPracticeGame (原始名称未知, 绑定见 EN_0_recv.yaml)
val Unk_92 = PKTHandler { c ->
    Decode4
    Decode2
    Decode1
    Decode4
    Decode1
    Decode1
    Decode4

    c.send(
        PracticeAiLevel(),
        PracticeSelectMap(),
        PracticeStartInfo(),
        PracticeBoard(),
    )

    // 启动游戏主循环 (onTick 爆炸检测与炸弹状态同步, aiTick 实体时间同步)
    Room.startGame()
}
