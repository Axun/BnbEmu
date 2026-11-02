package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 97: 切換遊戲階段 / 進入大廳 (線序 seq=52, 2B)
// ============================================================================
// 逆向確認:
//   CA.exe 0x00502B4B (CStage::OnPacket) -> 0x004F895D (切換 Stage 分派).
//   客戶端讀取 Encode2(stageId):
//     - 若 stageId == 11 (0x0B, 大廳):
//         調用 CMainSystem::Instance().SetStage(11) (0x0068DC53),
//         接著調用 0x0068D80F 自動向伺服器發送 C→S op 78 (StageInitReq).
//     - 若 stageId == 20 (0x14, 廣場/Square):
//         調用 CMainSystem::Instance().SetStage(20),
//         接著調用 0x0068D84E 自動向伺服器發送 C→S op 366 (Square StageInitReq).
//   在舊版 TW_5 / CN_12 協議中對應 EnterLobbyStage (TW_5 op 15).
fun ChangeStage(stageId: Int = 11) = PKT {
    Encode2(stageId)
}
