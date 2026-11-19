package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 84 — 快速加入房間結果 (JoinFreeSessionResult)
// ============================================================================
// C→S op 77 (JoinFreeSessionReq, 大廳「快速加入」按鈕) 的回應。
//
// 客戶端處理鏈 (reference/CA.exe.c, EN v18):
//   CLobbyStage::OnPacket 0x00510287 的 switch case 'T' (0x54 = 84) → sub_507CC7 (224146)
//     vtable[272](0) 清掉 op 77 發包點 (sub_50D4DC / CA.exe.c:228604) 設的等待鎖
//     switch (Decode1 result):
//       case 0  成功 → E2(房號) + E4(序號) + E1(房型) + E1(狀態)
//               寫進 CFxContext (sub_68739E / sub_68626F / sub_687393),
//               sub_505123 按房號到 op 87 房快取找房, 大廳策略虛表 +0x14 切進房間場景
//       case 2..12 失敗 → **只彈對話框, 不再讀任何欄位**
//     ⚠️ 沒有 case 1; 失敗包因此只需 1 個位元組。
//
// 錯誤碼 → 客戶端彈窗字串 (sub_507CC7 逐 case 的 sub_40274F):
//   2  CantFindEmptyRoom                    找不到空房間 ← 模擬器目前固定回這個
//   3  InvalidGrade                         等級/段位不符
//   4  InvalidLucci                         金幣不足
//   5  InvalidGachapon                     轉蛋限制
//   6  DontHaveTMItemJoin                  缺少入場道具
//   7  UserRejectJoinSession               房主拒絕
//   8  InvalidLevel                        等級限制
//   9  DeferShutdown                       停機倒數
//   10 InvalidWinRate                      勝率不符
//   11 InvalidGradeSeason                  賽季段位不符
//   12 HordeEvent_RecommendBuyHordeTicket  需買討伐券
//
// 與 op 83 EnterSessionResult 的差別: 83 是「進指定房」, case 1 = WrongPassword;
// 84 是「自動找房」, case 2 = CantFindEmptyRoom。兩者成功分支的欄位也不同
// (83: E1(result)+E1(flag)+E2+E1+E1; 84: E1(result)+E2+E4+E1+E1)。
// ============================================================================

/** 2 = 找不到空房間 */
const val JOIN_FREE_NO_ROOM = 2

fun JoinFreeSessionResult(
    result: Int = JOIN_FREE_NO_ROOM,
    session: Int = 0,
    seq: Int = 0,
    sessionType: Int = 0,
    state: Int = 0,
) = PKT {
    Encode1(result)
    if (result == 0) {
        Encode2(session)
        Encode4(seq)
        Encode1(sessionType)
        Encode1(state)
    }
}
