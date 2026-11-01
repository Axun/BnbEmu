package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.PKTHandler
import pingu.packet.PracticeAiLevel
import pingu.packet.PracticeBoard
import pingu.packet.PracticeSelectMap
import pingu.packet.PracticeStartInfo
import pingu.server.Room

// ============================================================================
// C→S op 92: 開始遊戲請求 (StartGameReq) — 房間/練習房內點擊"開始"後發出
// ============================================================================
// 逆向確認:
//   CA.exe 0x00528071 (CPrepareStrategyAIBattle::OnButtonClick / 房間開始序列).
//   客戶端在 0x00528076 構造 CSendPacket(0x5C = 92), 並於 0x00531A09 寫入欄位:
//     Encode4: mapId (地圖 ID, 如 0xAF = 175)
//     Encode2: 28
//     Encode1: 0
//     Encode4: 172
//     Encode1: 0
//     Encode1: 1
//     Encode4: 0
//   (17B body, tst.pcap stream4 sq=91).
//   在舊版 TW_5 / CN_12 協議中對應 StartGameReq (TW_5 op 106).
// 伺服器回覆對局啟動序列:
//   [111 PracticeAiLevel, 112 PracticeSelectMap, 100 PracticeStartInfo, 136 PracticeBoard].
// 之後客戶端完成加載發送 149 Ready Ack, 伺服器啟動對局主循環 (Room.startGame).
val StartGameReq = PKTHandler { c ->
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

    // 啟動遊戲主循環 (onTick 爆炸檢測與炸彈狀態同步, aiTick 實體時間同步)
    Room.startGame()
}
