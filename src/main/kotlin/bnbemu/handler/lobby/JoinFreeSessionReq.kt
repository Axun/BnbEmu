package bnbemu.handler.lobby

import bnbemu.constants.MAX_SESSION_SLOTS
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.lobby.JOIN_FREE_NO_ROOM
import bnbemu.packet.lobby.JoinFreeSessionResult
import org.tinylog.kotlin.Logger

// ============================================================================
// C→S op 77 — 快速加入房間請求 (JoinFreeSessionReq)
// ============================================================================
// 大廳「快速加入 / 自動配對」按鈕。舊 recv 表沒有這個 opcode (UNKNOWN 77)。
//
// 請求結構 (CA.exe 0x0050D4DC / sub_50D4DC, 發包點 CA.exe.c:228604):
//   CSendPacket_CtorWithOp(&pkt, 77)
//   Packet_Write_v68_Alias(&pkt, GetLocalPlayerCount_Field12())   E1 本地玩家數
//   do Packet_Write_v68(&pkt, v24[v4++]) while (...)               N×E1 每個本地玩家的角色 id
//   Packet_Write_v72(&pkt, a2)                                     E2
//   Packet_Write_v72(&pkt, a3)                                     E2
//   CSendPacket_Send(&pkt) → stage->vtable[272](1) 設等待鎖
// 實包 body (debug 日誌): 5B 5A A5 0D A5 69
//   5B    = E1(1)  本地玩家 1 人
//   5A    = E1(0)  角色 id 0
//   A5 0D = E2(100)
//   A5 69 = E2(0)
// 末兩欄由 sub_50D4DC 的 a2 / a3 傳入 (呼叫點 229835 / 233888 / 429697 / 598962),
// 與等級/地圖/模式篩選有關, 模擬器不使用, 但**必須消費**否則解析指標錯位。
//
// 回應: S→C op 84 JoinFreeSessionResult (opcode_send.yaml), 見 packet/JoinFreeSessionResult.kt。
// 模擬器目前不實作自動匹配房間, 依需求固定回 case 2 "CantFindEmptyRoom" (1 位元組),
// 客戶端會清等待鎖並彈錯誤框, 不會切進房間場景 —— 這正是與 op 85 房間詳情的差別。
// ============================================================================
val JoinFreeSessionReq = PKTHandler { c ->
    val playerCount = Decode1.coerceIn(0, MAX_SESSION_SLOTS)
    val localBombers = List(playerCount) { Decode1 }
    val filter1 = Decode2
    val filter2 = Decode2

    Logger.info(
        "[JoinFreeSessionReq] players=$playerCount bombers=$localBombers " +
            "filter=($filter1,$filter2) -> CantFindEmptyRoom"
    )

    c send JoinFreeSessionResult(JOIN_FREE_NO_ROOM)
}
