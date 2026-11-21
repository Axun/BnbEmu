package bnbemu.packet.lobby

import bnbemu.constants.MAX_SESSION_SLOTS
import bnbemu.netty.PKT

// ============================================================================
// S→C op 85 — 房間詳情回應 (RoomDetailInfo / CRoomDetailInfoDialog)
// ============================================================================
// C→S op 67 (RoomDetailInfoReq) 的回應。大廳點房間列表裡的房間詳情時收到本包,
// 彈出 "Room Detail Info Dialog" 顯示房內玩家名單與剩餘時間 (不切房間場景)。
//
// ---- 客戶端解析鏈 (reference/CA.exe.c, EN v18) ----
// 1) CLobbyStage::OnPacket 0x00510287 讀 Packet_ReadUInt16 拿 opcode,
//    case 'U' (0x55 = 85) → sub_50702E (CA.exe.c:223507):
//      (*this)[68](this, 0)          清大廳等待鎖 —— 正是 op 67 發包點
//                                    sub_50FE46 (0x0050FE46) 結尾 vtable[272](1) 設的那把鎖
//      if (Decode1() != 0)           有效 → 調大廳策略虛表 +0x1C 讀名單 (見 2)
//      else                          無效 → 彈 "NotValidRoomInfo"
//    ⚠️ 成功碼是非 0 (用 1), 且 **只有這 1 個位元組** 屬於 sub_50702E 自己,
//      後面全部交給 strategy, 沒有房號/序號/房型欄位。
// 2) 大廳策略 +0x1C 的實作 (6 個 strategy 各一份, 都是 sub_4AE027 建構
//    CRoomDetailInfoDialog 後逐槽讀):
//      sub_515274 (0x00515274, 8 槽, "Lobby/Dlg_LB-RoomInfo8")
//      sub_514978 (0x00514978, 12 槽, "Lobby/Dlg_LB-RoomInfo12")
//      sub_5161D8 / sub_516FC9 / sub_51808A / sub_518B6E (其它大廳)
//      for i in 0..槽數-1:
//          if (Decode1() != 0):                       槽位有人
//              v2 = Decode1()                        user+104 (真端無寫入者, 恆 0)
//              sub_78FDF0(pkt, &name)                DecodeStr → 0x75DAF0 令牌化字串
//              look = Decode2()                      等級 / 外觀等級
//              play = Decode1()                      0 未開局 / 1 遊戲中 / 2 已陣亡
//              chief = Decode1()                     1 = 房主
//              sub_4ADFDF(dlg, i, ...)               填第 i 個槽位
//      time = Decode4()                             剩餘時間 (ms; sub_4ADBED /1000 %60 顯示分鐘)
//      sub_79D70A(...)                              顯示對話框
//    普通大廳走 8 槽版本, 故這裡固定發 8 個槽位塊。
//
// 字串: sub_78FDF0 → sub_78F910 在 degree 1..3 會調 0x0075DAF0 反混淆,
//       所以必須用 EncodeCipherStr (E2 長度 + SSD/令牌變換), 明文會顯示亂碼。
// ============================================================================

/** 房間詳情裡的一個佔用槽位; null = 空槽 / AI 槽 (客戶端只發 E1(0))。 */
data class RoomDetailSlot(
    val name: String,
    val level: Int = 1,
    /** 0 未開局 / 1 遊戲中 / 2 已陣亡 (真端 CGameSession::OnRequestSessionInfoDetail) */
    val playState: Int = 0,
    /** 1 = 房主 (對應 Slot.state 的 FLAG_CHIEF) */
    val chief: Int = 0,
)

fun RoomDetailInfo(
    slots: List<RoomDetailSlot?>,
    /** 非 0 = 房間資料有效; 0 會讓客戶端彈 "NotValidRoomInfo" */
    valid: Int = 1,
    remainMs: Int = 0,
) = PKT {
    Encode1(valid)
    // 失敗分支 (真端 GameSessionTable.ts:147) 只發這 1 個位元組, 客戶端不再讀欄位
    if (valid == 0) return@PKT

    repeat(MAX_SESSION_SLOTS) { i ->
        val slot = slots.getOrNull(i)
        Encode1(if (slot == null) 0 else 1)
        if (slot != null) {
            Encode1(0)                    // user+104: 真端沒有寫入者, 恆 0
            EncodeCipherStr(slot.name)   // 0x75DAF0 令牌化角色名 (長度 E2)
            Encode2(slot.level)
            Encode1(slot.playState)
            Encode1(slot.chief)
        }
    }

    Encode4(remainMs)
}
