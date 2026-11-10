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
//
//    商城舞台走的是**另一個** op 97 處理器: `CShopStageNew::OnPacket` (0x005D5CBA) 的
//    case 97 → `sub_5CDD15` (0x005CDD15, 另有逐字節相同的 sub_62F728 掛在其他舞台虛表)。
//    它對 stageId 的分派更寬: 12/16/17/18 → 發 C→S op 80 後 SetStage(stageId) 回房間,
//    其餘值才按 sub_67CDCE() 去廣場(20) 或大廳(11)。所以退出商城時這個 stageId
//    決定了「回大廳還是回房間」, 不能固定填 11 (見 handler/StageStateSyncReq.kt)。

/** 舞台 id (CMainSystem::SetStage 的参数) */
enum class ClientStage(val value: Int) {
    /** 大厅 CLobbyStage */
    Lobby(11),
    /** 房间 CSlotStage / CPrepareStage (练习房、普通房) */
    Room(12),
    /** 对局 CGameStage */
    Game(13),
    /** 商城 CShopStageNew */
    Shop(14),
    /** 广场 */
    Square(20)
}

/**
 * 客户端 `sub_4F748F` (0x004F748F) 判定为「会话/房间舞台」的 id 集合:
 * `a1 == 12 || a1 == 18 || a1 == 16 || a1 == 17`。
 *
 * 商城舞台 `CShopStageNew::OnPacket` (0x005D5CBA) 的 op 97 分支 → `sub_5CDD15`
 * (0x005CDD15) 读 `E2(stageId)` 后:
 *   · stageId 在本集合 → 先发 C→S op 80 (E2 + E4 会话信息), 再 `SetStage(stageId)` 回房间;
 *   · 其他值         → 特殊模式去广场(20), 否则 `SetStage(11)` + 发 C→S op 78 回大厅。
 * 所以「从房间进商城后返回」唯一正确的包是 ChangeStage(12), 而不是 ChangeStage(11)。
 */
val ROOM_STAGE_IDS = setOf(ClientStage.Room.value, 16, 17, 18)

/** stageId 是否会让客户端切回房间舞台 */
fun isRoomStage(stageId: Int): Boolean = stageId in ROOM_STAGE_IDS

fun ChangeStage(stageId: Int = ClientStage.Lobby.value) = PKT {
    Encode2(stageId)
}
