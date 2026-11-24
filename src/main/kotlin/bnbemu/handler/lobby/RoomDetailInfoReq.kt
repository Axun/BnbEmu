package bnbemu.handler.lobby

import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.lobby.RoomDetailSlot
import bnbemu.packet.lobby.RoomDetailInfo
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.Room
import bnbemu.server.state
import bnbemu.service.PlayerService
import bnbemu.service.player
import org.tinylog.kotlin.Logger

// ============================================================================
// C→S op 67 — 房間 / 會話詳情請求 (RoomDetailInfoReq)
// ============================================================================
// 觸發: 大廳房間列表裡點某一間房看詳情 (玩家列表、状态、对局剩余时间等)。

//
// ---- 請求結構 (反編譯 + 實包雙重驗證) ----
//   CA.exe 0x0050FE46 (sub_50FE46, 房間列表索引 → 快取條目):
//     CSendPacket_CtorWithOp(&pkt, 67)
//     v5 = sub_4DC798(room + 44)          // WORD 房號 (op 87 快取條目的 +44)
//     Packet_Write_v72(&pkt, v5)          // E2
//     Packet_Write_v76(&pkt, *(room+68))  // E4 會話序號
//     CSendPacket_Send(&pkt)
//     stage->vtable[272](1)               // 設「等伺服器回應」鎖
//   另一處 0x00508CDA (CA.exe.c:225187, 手輸房號那條路徑) 欄位完全相同。
//
//   實包 (debug 日誌, body 6B): A5 54 96 CA 53 E2
//     A5 54       = Encode2(61)  → 房號 61 = op 87 RoomList 的 rd 房 f3>>16 (0x3D0000 >> 16)
//     96 CA 53 E2 = Encode4(119) → 序號 119 = 同一筆條目的 f4
//
// ---- 回應 (2026-11-15 實測更正) ----
//   S→C **op 85** RoomDetailInfo (opcode_send.yaml), 見 packet/RoomDetailInfo.kt。
//   客戶端 case 'U' → sub_50702E 清掉上面的等待鎖, E1(1) 後轉大廳策略 +0x1C
//   讀 8 槽名單並彈 CRoomDetailInfoDialog (E1(0) 則彈 "NotValidRoomInfo")。
//   請求帶來的房號/序號只用於校驗與紀錄, op 85 的 body 不含這兩個欄位。
// ============================================================================
val RoomDetailInfoReq = PKTHandler { c ->
    val sessionId = Decode2
    val serial = Decode4

    val self = c.player
    val selfName = self?.charName?.ifEmpty { PlayerService.activeProfile.charName }
        ?: PlayerService.activeProfile.charName

    // 房內名單以 Room.current?.slots 為準: 只列真人, AI/空槽發 E1(0) 讓客戶端留白
    // (真端 CGameSession::m_rgPlayer 也只放真人; 練習房的 AI 屬 slotState, 不在名單裡)。
    val slots: MutableList<RoomDetailSlot?> = Room.current?.slots?.map { slot ->
        val player = slot.player ?: return@map null
        RoomDetailSlot(
            name = if (slot.client === c) selfName.ifEmpty { player.charName } else player.charName,
            level = player.level,
            playState = 0,                 // 未開局
            chief = if ((slot.state.toInt() and FLAG_CHIEF) != 0) 1 else 0,
        )
    }?.toMutableList() ?: MutableList(MAX_SESSION_SLOTS) { null }

    // 玩家還在大廳、尚未建房時 Room 是全空的。這時房間列表顯示的 rd 房 (1/1)
    // 至少要有房主, 否則詳情對話框 8 個槽全白, 看不出回包有沒有生效。
    if (slots.all { it == null } && self != null) {
        slots[0] = RoomDetailSlot(
            name = selfName.ifEmpty { self.charName },
            level = self.level,
            chief = 1,
        )
    }

    val desc = slots.mapIndexedNotNull { i, s ->
        if (s == null) null else {
            val chiefMark = if (s.chief == 1) "(房主)" else ""
            "$i:${s.name} Lv${s.level}$chiefMark"
        }
    }
    Logger.info("[RoomDetailInfoReq] 房號=$sessionId 序號=$serial 名單=$desc")

    c send RoomDetailInfo(slots = slots)
}
